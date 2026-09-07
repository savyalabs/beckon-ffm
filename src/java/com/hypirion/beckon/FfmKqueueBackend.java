package com.hypirion.beckon;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import clojure.lang.ExceptionInfo;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashSet;
import clojure.lang.PersistentArrayMap;
import clojure.lang.Seqable;

/**
 * EXPERIMENTAL {@link SignalBackend} for macOS/BSD, built on the Foreign
 * Function and Memory API (JDK 22+) using {@code kqueue(2)} with
 * {@code EVFILT_SIGNAL} instead of {@code sun.misc.Signal}. Opt-in only
 * ({@code -Dbeckon.signal.backend=ffm}); selected automatically on macOS.
 *
 * <p>Each managed signal is set to {@code SIG_IGN} (so its default action does
 * not fire) and registered on a kqueue; a dispatcher thread blocks in
 * {@code kevent(2)} and runs the handlers when a signal is delivered. Because
 * {@code SIG_IGN} is a process-wide disposition (not a per-thread block), this
 * backend - unlike the Linux signalfd one - also observes signals sent from
 * outside the process (e.g. {@code kill -HUP}). It remains experimental.
 *
 * <p>macOS/BSD only. Constructing it elsewhere throws
 * {@link UnsupportedOperationException}.
 */
public final class FfmKqueueBackend implements SignalBackend {

    // Catchable, non-JVM-reserved macOS/BSD signals. These numbers differ from
    // Linux, especially USR1/USR2; SIGKILL and SIGSTOP are not catchable.
    private static final Map<String, Integer> SIGNOS = new LinkedHashMap<>();
    static {
        SIGNOS.put("HUP", 1);   SIGNOS.put("INT", 2);   SIGNOS.put("QUIT", 3);
        SIGNOS.put("ILL", 4);   SIGNOS.put("TRAP", 5);  SIGNOS.put("ABRT", 6);
        SIGNOS.put("EMT", 7);   SIGNOS.put("FPE", 8);   SIGNOS.put("BUS", 10);
        SIGNOS.put("SEGV", 11); SIGNOS.put("SYS", 12);  SIGNOS.put("PIPE", 13);
        SIGNOS.put("ALRM", 14); SIGNOS.put("TERM", 15); SIGNOS.put("URG", 16);
        SIGNOS.put("TSTP", 18); SIGNOS.put("CONT", 19); SIGNOS.put("CHLD", 20);
        SIGNOS.put("TTIN", 21); SIGNOS.put("TTOU", 22); SIGNOS.put("IO", 23);
        SIGNOS.put("XCPU", 24); SIGNOS.put("XFSZ", 25); SIGNOS.put("VTALRM", 26);
        SIGNOS.put("PROF", 27); SIGNOS.put("WINCH", 28); SIGNOS.put("INFO", 29);
        SIGNOS.put("USR1", 30);
    }

    /** Return the signal names accepted by this platform backend. */
    public static Set<String> staticSupportedSignals() {
        return Collections.unmodifiableSet(SIGNOS.keySet());
    }

    @Override
    public Set<String> supportedSignals() {
        return staticSupportedSignals();
    }

    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new IllegalStateException(
            "missing native symbol '" + name
            + "' for macOS/BSD kqueue backend; check native access and libc"));
    }

    // kqueue / struct kevent constants (macOS, 64-bit).
    private static final short EVFILT_SIGNAL = -6;
    private static final short EVFILT_USER = -10;
    private static final short EV_ADD    = 0x0001;
    private static final short EV_DELETE = 0x0002;
    private static final int NOTE_TRIGGER = 0x01000000;
    private static final long  SIG_DFL = 0L;
    private static final long  SIG_IGN = 1L;
    private static final int   KEVENT_SIZE = BsdAbi.KEVENT_SIZE;

    private final MethodHandle kqueueFn; // int kqueue(void)
    private final MethodHandle kevent;   // int kevent(int, kevent*, int, kevent*, int, timespec*)
    private final MethodHandle signalFn; // sig_t signal(int, sig_t)
    private final MethodHandle kill;     // int kill(pid_t, int)
    private final MethodHandle getpid;   // pid_t getpid(void)
    private final MethodHandle closeFn;  // int close(int)
    private final Thread dispatcherThread;

    private final Arena arena = Arena.ofShared();
    private final Map<Integer, Seqable> registry = new ConcurrentHashMap<>();
    private final Map<Integer, Long> previousDispositions = new ConcurrentHashMap<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile int kq = -1;
    private volatile boolean running = true;
    private boolean closed;

    private final Object raiseLock = new Object();
    private volatile CountDownLatch raiseDone;

    public FfmKqueueBackend() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!(os.contains("mac") || os.contains("darwin") || os.contains("bsd"))) {
            throw new UnsupportedOperationException(
                "beckon FFM kqueue backend requires macOS/BSD; this is "
                + System.getProperty("os.name"));
        }
        BsdAbi.validateCurrentPlatform();
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        kqueueFn = linker.downcallHandle(symbol(libc, "kqueue"),
            FunctionDescriptor.of(JAVA_INT));
        kevent = linker.downcallHandle(symbol(libc, "kevent"),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        signalFn = linker.downcallHandle(symbol(libc, "signal"),
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS),
            Linker.Option.captureCallState("errno"));
        kill = linker.downcallHandle(symbol(libc, "kill"),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        getpid = linker.downcallHandle(symbol(libc, "getpid"),
            FunctionDescriptor.of(JAVA_INT));
        closeFn = linker.downcallHandle(symbol(libc, "close"),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        dispatcherThread = new Thread(this::dispatch, "beckon-ffm-kqueue");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatch() {
        try {
            kq = (int) kqueueFn.invokeExact();
            if (kq < 0) throw new IllegalStateException("kqueue() failed");
            changeKevent(0, EVFILT_USER, EV_ADD, 0);
        } catch (Throwable e) {
            running = false;
            ready.countDown();
            throw new RuntimeException("FFM kqueue backend init failed", e);
        }
        ready.countDown();

        MemorySegment evlist = arena.allocate((long) KEVENT_SIZE * 16);
        while (running) {
            int n;
            try {
                n = (int) kevent.invokeExact(kq, MemorySegment.NULL, 0,
                                             evlist, 16, MemorySegment.NULL);
            } catch (Throwable e) {
                if (!running) break;
                continue;
            }
            if (n <= 0) {
                if (!running) break;
                continue;
            }
            for (int i = 0; i < n; i++) {
                long ident = !BsdAbi.word64(System.getProperty("os.arch", ""))
                    ? evlist.get(JAVA_INT, (long) i * KEVENT_SIZE)
                    : evlist.get(JAVA_LONG, (long) i * KEVENT_SIZE);
                if (ident == 0) return;
                fold(registry.get((int) ident));
            }
            CountDownLatch done = raiseDone;
            if (done != null) done.countDown();
        }
    }

    /** Add or remove an EVFILT_SIGNAL registration for signo. */
    private void changeKevent(int signo, short flags) {
        changeKevent(signo, EVFILT_SIGNAL, flags, 0);
    }

    private void changeKevent(int ident, short filter, short flags, int fflags) {
        try {
            MemorySegment kev = arena.allocate(KEVENT_SIZE);
            if (BsdAbi.word64(System.getProperty("os.arch", "")))
                kev.set(JAVA_LONG, BsdAbi.IDENT_OFFSET, ident);
            else
                kev.set(JAVA_INT, BsdAbi.IDENT_OFFSET, (int) ident);
            kev.set(JAVA_SHORT, 8, filter);         // filter
            kev.set(JAVA_SHORT, 10, flags);        // flags
            kev.set(JAVA_INT, 12, fflags);          // fflags
            kev.set(JAVA_LONG, 16, 0L);            // data
            if (BsdAbi.word64(System.getProperty("os.arch", "")))
                kev.set(JAVA_LONG, BsdAbi.UDATA_OFFSET, 0L);
            else
                kev.set(JAVA_INT, BsdAbi.UDATA_OFFSET, 0);
            int r = (int) kevent.invokeExact(kq, kev, 1,
                                             MemorySegment.NULL, 0, MemorySegment.NULL);
            requireZero("kevent() change", r);
        } catch (Throwable e) {
            throw new RuntimeException("kevent() change failed", e);
        }
    }

    private void wakeDispatcher() {
        try {
            changeKevent(0, EVFILT_USER, (short) 0, NOTE_TRIGGER);
        } catch (RuntimeException ignored) {
            // The dispatcher can already have exited after a prior close.
        }
    }

    private void closeNativeDescriptor() {
        int descriptor = kq;
        kq = -1;
        if (descriptor >= 0) {
            try {
                int result = (int) closeFn.invokeExact(descriptor);
                if (result != 0) throw new IllegalStateException("return=" + result);
            } catch (Throwable e) {
                throw new RuntimeException("close() failed", e);
            }
        }
    }

    /** Stop dispatching, restore signal state, and release native resources. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        wakeDispatcher();
        if (Thread.currentThread() != dispatcherThread) {
            boolean interrupted = false;
            while (dispatcherThread.isAlive()) {
                try {
                    dispatcherThread.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        closeNativeDescriptor();
        for (Map.Entry<Integer, Long> entry : previousDispositions.entrySet()) {
            setDisposition(entry.getKey(), entry.getValue());
        }
        registry.clear();
        previousDispositions.clear();
        arena.close();
    }

    /** Set the process-wide disposition of signo and return its previous value. */
    private long setDisposition(int signo, long handler) {
        MemorySegment old;
        int errno;
        try {
            MemorySegment callState = arena.allocate(Linker.Option.captureStateLayout());
            old = (MemorySegment) signalFn.invokeExact(callState, signo,
                                                       MemorySegment.ofAddress(handler));
            errno = callState.get(JAVA_INT, 0);
        } catch (Throwable e) {
            throw new RuntimeException("signal() failed", e);
        }
        return signalResult("signal()", old.address(), errno);
    }

    private static void requireZero(String operation, int result) {
        if (result != 0) {
            throw new ExceptionInfo(operation + " failed",
                PersistentArrayMap.create(Map.of(Keyword.intern("return"), result)));
        }
    }

    private static long signalResult(String operation, long result, int errno) {
        if (result == -1L) {
            throw new ExceptionInfo(operation + " failed",
                PersistentArrayMap.create(Map.of(Keyword.intern("return"), result,
                                                 Keyword.intern("errno"), errno)));
        }
        return result;
    }

    private static int signo(String signame) {
        Integer n = SIGNOS.get(signame);
        if (n == null) {
            throw new IllegalArgumentException(
                "Unsupported signal for FFM backend: " + signame);
        }
        return n;
    }

    @Override
    public synchronized void register(String signame, Seqable runnables) {
        int signo = signo(signame);
        boolean firstRegistration = !registry.containsKey(signo);
        registry.put(signo, runnables);
        long previous = setDisposition(signo, SIG_IGN);
        if (firstRegistration) previousDispositions.put(signo, previous);
        changeKevent(signo, EV_ADD);
    }

    @Override
    public synchronized void reset(String signame) {
        int signo = signo(signame);
        registry.remove(signo);
        changeKevent(signo, EV_DELETE);
        setDisposition(signo, previousDispositions.getOrDefault(signo, SIG_DFL));
        previousDispositions.remove(signo);
    }

    @Override
    public synchronized void resetAll() {
        for (Integer signo : new ArrayList<>(registry.keySet())) {
            registry.remove(signo);
            changeKevent(signo, EV_DELETE);
            setDisposition(signo, previousDispositions.getOrDefault(signo, SIG_DFL));
            previousDispositions.remove(signo);
        }
    }

    @Override
    public Seqable currentRunnables(String signame) {
        Seqable s = registry.get(signo(signame));
        return s != null ? s : PersistentHashSet.EMPTY;
    }

    @Override
    public void raise(String signame) {
        int signo = signo(signame);
        // Serialize and wait for the dispatcher to fold, so raises don't bleed
        // across handler sets (same contract as the signalfd backend).
        synchronized (raiseLock) {
            CountDownLatch done = new CountDownLatch(1);
            raiseDone = done;
            try {
                int pid = (int) getpid.invokeExact();
                int r = (int) kill.invokeExact(pid, signo); // process-directed
                requireZero("kill", r);
            } catch (Throwable e) {
                raiseDone = null;
                throw new RuntimeException("kill failed for " + signame, e);
            }
            try {
                done.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            raiseDone = null;
        }
    }

    private static void fold(Seqable fns) {
        if (fns == null) return;
        for (ISeq s = fns.seq(); s != null; s = s.next()) {
            try {
                ((Runnable) s.first()).run();
            } catch (Exception e) {
                break;
            }
        }
    }
}
