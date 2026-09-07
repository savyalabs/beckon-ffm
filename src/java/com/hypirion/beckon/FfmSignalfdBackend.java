package com.hypirion.beckon;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;

import clojure.lang.ExceptionInfo;
import clojure.lang.ISeq;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashSet;
import clojure.lang.Seqable;

/**
 * EXPERIMENTAL {@link SignalBackend} built entirely on the Foreign Function and
 * Memory API (JDK 22+), using Linux {@code signalfd(2)} instead of
 * {@code sun.misc.Signal}. It is <strong>opt-in only</strong>
 * ({@code -Dbeckon.signal.backend=ffm}) and never the default.
 *
 * <p><strong>Known limitation.</strong> {@code signalfd} only receives a signal
 * if that signal is blocked in the thread it would otherwise be delivered to.
 * The JVM starts many threads before beckon loads, and we cannot retroactively
 * change their signal masks, so a process-directed signal from <em>outside</em>
 * (e.g. {@code kill -HUP}) will usually be taken by some other JVM thread and
 * run its default action rather than reaching this backend. This backend
 * therefore reliably handles only beckon's own {@link #raise(String)} (directed
 * at the dispatcher thread via {@code pthread_kill}); it is a demonstration of
 * the modern interop, not a production replacement for the sun.misc backend.
 * That limitation is the reason sun.misc remains beckon's default.
 *
 * <p>Linux only. Constructing it elsewhere throws
 * {@link UnsupportedOperationException}.
 */
public final class FfmSignalfdBackend implements SignalBackend {

    // --- Linux constants (x86_64 / aarch64) ---------------------------------
    private static final int SIG_BLOCK   = 0;
    private static final int SFD_CLOEXEC = 0x80000;
    private static final int SFD_NONBLOCK = 0x800;
    private static final int EFD_CLOEXEC = 0x80000;
    private static final int HOTSPOT_SIGNAL = 12; // SIGUSR2 on Linux
    private static final short POLLIN = 0x0001;
    private static final int SIGSET_SIZE = LinuxAbi.SIGSET_SIZE;
    private static final int SIGINFO_SIZE = LinuxAbi.SIGINFO_SIZE;
    private static final long SIG_DFL = 0L;
    private static final long SIG_IGN = 1L;

    /**
     * Catchable, non-JVM-reserved Linux signals, by POSIX short name.
     * SIGKILL and SIGSTOP cannot be caught; SIGUSR2 and fatal VM signals are
     * reserved by HotSpot and intentionally stay out of this map.
     */
    private static final Map<String, Integer> SIGNOS = new LinkedHashMap<>();
    static {
        SIGNOS.put("HUP", 1);   SIGNOS.put("INT", 2);   SIGNOS.put("QUIT", 3);
        SIGNOS.put("USR1", 10); SIGNOS.put("TERM", 15);
        SIGNOS.put("CHLD", 17); SIGNOS.put("CONT", 18); SIGNOS.put("TSTP", 20);
        SIGNOS.put("WINCH", 28); SIGNOS.put("ALRM", 14); SIGNOS.put("TTIN", 21);
        SIGNOS.put("TTOU", 22); SIGNOS.put("URG", 23); SIGNOS.put("XCPU", 24);
        SIGNOS.put("VTALRM", 26); SIGNOS.put("PROF", 27); SIGNOS.put("IO", 29);
        SIGNOS.put("PWR", 30);
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
            + "' for Linux signalfd backend; check native access and libc"));
    }

    // --- native handles ------------------------------------------------------
    private final MethodHandle signalfd;     // int signalfd(int, sigset_t*, int)
    private final MethodHandle read;         // ssize_t read(int, void*, size_t)
    private final MethodHandle sigemptyset;  // int sigemptyset(sigset_t*)
    private final MethodHandle sigaddset;    // int sigaddset(sigset_t*, int)
    private final MethodHandle pthreadSigmask; // int(int, sigset_t*, sigset_t*)
    private final MethodHandle pthreadSelf;  // pthread_t pthread_self(void)
    private final MethodHandle pthreadKill;  // int pthread_kill(pthread_t, int)
    private final MethodHandle signalFn;     // sighandler_t signal(int, sighandler_t)
    private final MethodHandle eventfd;      // int eventfd(unsigned int, int)
    private final MethodHandle poll;         // int poll(struct pollfd*, nfds_t, int)
    private final MethodHandle write;        // ssize_t write(int, void*, size_t)
    private final MethodHandle closeFn;      // int close(int)

    private final Arena arena = Arena.ofShared();
    private final Map<Integer, Seqable> registry = new ConcurrentHashMap<>();
    private final Map<Integer, Long> previousDispositions = new ConcurrentHashMap<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Set<Integer> externalAllowlist;
    private final Thread dispatcherThread;

    private volatile int fd = -1;
    private volatile int wakeFd = -1;
    private volatile long dispatcherPthread = 0;
    private volatile boolean running = true;
    private boolean closed;

    // Serializes raise() and lets it wait for the dispatcher to fold the read it
    // triggered, so signals never bleed from one raise! into a later one.
    private final Object raiseLock = new Object();
    private volatile CountDownLatch raiseDone;

    public FfmSignalfdBackend() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new UnsupportedOperationException(
                "beckon FFM signal backend requires Linux (signalfd); this is "
                + System.getProperty("os.name"));
        }
        LinuxAbi.validateCurrentPlatform();
        externalAllowlist = externalAllowlist();
        if (!externalAllowlist.isEmpty()) validateExternalStartup();
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        signalfd = linker.downcallHandle(symbol(libc, "signalfd"),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
        read = linker.downcallHandle(symbol(libc, "read"),
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
        sigemptyset = linker.downcallHandle(symbol(libc, "sigemptyset"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
        sigaddset = linker.downcallHandle(symbol(libc, "sigaddset"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        pthreadSigmask = linker.downcallHandle(symbol(libc, "pthread_sigmask"),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        pthreadSelf = linker.downcallHandle(symbol(libc, "pthread_self"),
            FunctionDescriptor.of(JAVA_LONG));
        pthreadKill = linker.downcallHandle(symbol(libc, "pthread_kill"),
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
        signalFn = linker.downcallHandle(symbol(libc, "signal"),
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS),
            Linker.Option.captureCallState("errno"));
        eventfd = linker.downcallHandle(symbol(libc, "eventfd"),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
        poll = linker.downcallHandle(symbol(libc, "poll"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
        write = linker.downcallHandle(symbol(libc, "write"),
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
        closeFn = linker.downcallHandle(symbol(libc, "close"),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        dispatcherThread = new Thread(this::dispatch, "beckon-ffm-dispatch");
        dispatcherThread.setDaemon(true);
        dispatcherThread.start();
        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Return the launcher's allowlist, or empty when the opt-in mode is off. */
    private static Set<Integer> externalAllowlist() {
        String value = System.getenv("BECKON_EXTERNAL_SIGNALS");
        if (value == null) return Collections.emptySet();
        boolean unsafe = "1".equals(System.getenv("BECKON_EXTERNAL_ALLOW_UNSAFE"));
        Set<Integer> result = new HashSet<>();
        for (String name : value.split(",")) {
            Integer number = SIGNOS.get(name);
            if (number == null) throw new IllegalStateException(
                "invalid BECKON_EXTERNAL_SIGNALS entry: " + name);
            if ((name.equals("USR2") || name.equals("CHLD")) && !unsafe) {
                throw new IllegalStateException(
                    "refusing unsafe external signal " + name
                    + "; use the launcher's explicit unsafe override only after review");
            }
            result.add(number);
        }
        return Collections.unmodifiableSet(result);
    }

    private void validateExternalStartup() {
        if (!ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Xrs")) {
            throw new IllegalStateException(
                "external signal mode requires the JVM -Xrs flag for TERM/INT/HUP");
        }
        try {
            String status = Files.readString(Path.of("/proc/self/status"));
            String mask = status.lines().filter(line -> line.startsWith("SigBlk:"))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                    "/proc/self/status has no SigBlk field"))
                .substring("SigBlk:".length()).trim();
            BigInteger blocked = new BigInteger(mask, 16);
            for (Integer number : externalAllowlist) {
                if (!blocked.testBit(number - 1)) throw new IllegalStateException(
                    "external signal " + signalName(number)
                    + " is not blocked; launch through beckon-signal-launcher");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                "external signal mode requires Linux /proc/self/status SigBlk", e);
        }
    }

    private static String signalName(int number) {
        return SIGNOS.entrySet().stream().filter(entry -> entry.getValue() == number)
            .map(Map.Entry::getKey).findFirst().orElse(String.valueOf(number));
    }

    /** Build a native sigset_t containing the given signal numbers. */
    private MemorySegment sigset(Iterable<Integer> signos) throws Throwable {
        MemorySegment set = arena.allocate(SIGSET_SIZE);
        int r = (int) sigemptyset.invokeExact(set);
        requireZero("sigemptyset", r);
        for (int signo : signos) {
            r = (int) sigaddset.invokeExact(set, signo);
            requireZero("sigaddset", r);
        }
        return set;
    }

    /** Dispatcher thread: block all supported signals here, create the fd, loop. */
    private void dispatch() {
        try {
            dispatcherPthread = (long) pthreadSelf.invokeExact();
            // In external mode every JVM thread inherited this mask from the
            // pre-launch shim. Otherwise preserve the historical raise!-only
            // behavior by blocking supported signals in this dispatcher.
            Set<Integer> dispatcherSignals = new HashSet<>(externalAllowlist.isEmpty()
                ? SIGNOS.values() : externalAllowlist);
            // HotSpot uses SIGUSR2 for internal thread coordination. Keep it
            // out of poll(2)'s EINTR path even when external mode has a narrow
            // application allowlist.
            dispatcherSignals.add(HOTSPOT_SIGNAL);
            MemorySegment blockAll = sigset(dispatcherSignals);
            int r = (int) pthreadSigmask.invokeExact(SIG_BLOCK, blockAll, MemorySegment.NULL);
            requireZero("pthread_sigmask", r);
            // Start with an empty signalfd mask; register() adds to it.
            MemorySegment empty = sigset(java.util.Collections.emptyList());
            fd = (int) signalfd.invokeExact(-1, empty, SFD_CLOEXEC | SFD_NONBLOCK);
            if (fd < 0) {
                throw new IllegalStateException("signalfd() failed");
            }
            wakeFd = (int) eventfd.invokeExact(0, EFD_CLOEXEC);
            if (wakeFd < 0) throw new IllegalStateException("eventfd() failed");
        } catch (Throwable e) {
            running = false;
            ready.countDown();
            throw new RuntimeException("FFM signal backend init failed", e);
        }
        ready.countDown();

        MemorySegment buf = arena.allocate(SIGINFO_SIZE);
        MemorySegment wakeBuf = arena.allocate(JAVA_LONG);
        MemorySegment pollfds = arena.allocate((long) LinuxAbi.POLLFD_SIZE * 2);
        while (running) {
            pollfds.set(JAVA_INT, LinuxAbi.POLLFD_FD_OFFSET, fd);
            pollfds.set(JAVA_SHORT, LinuxAbi.POLLFD_EVENTS_OFFSET, POLLIN);
            pollfds.set(JAVA_SHORT, LinuxAbi.POLLFD_FIRST_REVENTS_OFFSET, (short) 0);
            pollfds.set(JAVA_INT, LinuxAbi.POLLFD_SIZE + LinuxAbi.POLLFD_FD_OFFSET, wakeFd);
            pollfds.set(JAVA_SHORT, LinuxAbi.POLLFD_SIZE + LinuxAbi.POLLFD_EVENTS_OFFSET, POLLIN);
            pollfds.set(JAVA_SHORT, LinuxAbi.POLLFD_SECOND_REVENTS_OFFSET, (short) 0);
            int ready;
            try {
                ready = (int) poll.invokeExact(pollfds, 2L, -1);
            } catch (Throwable e) {
                if (!running) break;
                continue;
            }
            if (!running) break;
            boolean wakeReady = (pollfds.get(JAVA_SHORT, LinuxAbi.POLLFD_SECOND_REVENTS_OFFSET)
                                 & POLLIN) != 0;
            boolean signalReady = (pollfds.get(JAVA_SHORT, LinuxAbi.POLLFD_FIRST_REVENTS_OFFSET)
                                   & POLLIN) != 0;
            if (wakeReady) {
                try {
                    long n = (long) read.invokeExact(wakeFd, wakeBuf, 8L);
                    if (n != 8L) continue;
                } catch (Throwable e) {
                    if (!running) break;
                    continue;
                }
                // close() uses the same eventfd wakeup. Do not touch the
                // signalfd or arena after the owner has requested shutdown.
                if (!running) break;
            }
            if (!signalReady) continue;
            long n;
            try {
                n = (long) read.invokeExact(fd, buf, (long) SIGINFO_SIZE);
            } catch (Throwable e) {
                if (!running) break;
                continue;
            }
            if (n < SIGINFO_SIZE) {
                if (!running) break;
                continue; // EINTR or short read; retry
            }
            int signo = buf.get(JAVA_INT, 0); // ssi_signo is the first field
            fold(registry.get(signo));
            CountDownLatch done = raiseDone;
            if (done != null) done.countDown();
        }
    }

    private long writeWakeup(int descriptor) throws Throwable {
        MemorySegment value = arena.allocate(JAVA_LONG);
        value.set(JAVA_LONG, 0, 1L);
        return (long) write.invokeExact(descriptor, value, 8L);
    }

    private void closeNativeDescriptor(int descriptor) {
        if (descriptor < 0) return;
        try {
            int result = (int) closeFn.invokeExact(descriptor);
            if (result != 0) throw new IllegalStateException("return=" + result);
        } catch (Throwable e) {
            throw new RuntimeException("close() failed", e);
        }
    }

    /** Stop dispatching, restore signal state, and release native resources. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        int descriptor = wakeFd;
        if (descriptor >= 0) {
            try {
                long ignored = writeWakeup(descriptor);
            } catch (Throwable ignored) {
                // The dispatcher may already have exited after initialization failed.
            }
        }
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
        int signalDescriptor = fd;
        fd = -1;
        int wakeDescriptor = wakeFd;
        wakeFd = -1;
        closeNativeDescriptor(signalDescriptor);
        closeNativeDescriptor(wakeDescriptor);
        for (Map.Entry<Integer, Long> entry : previousDispositions.entrySet()) {
            setDisposition(entry.getKey(), entry.getValue());
        }
        registry.clear();
        previousDispositions.clear();
        arena.close();
    }

    /** Run each Runnable in order, stopping on the first Exception (Errors propagate). */
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

    /** Update the signalfd mask to exactly the currently-registered signals. */
    private synchronized void rebuildMask() {
        try {
            MemorySegment set = sigset(registry.keySet());
            int r = (int) signalfd.invokeExact(fd, set, SFD_CLOEXEC);
            if (r < 0) {
                throw new ExceptionInfo("signalfd() mask update failed",
                    PersistentArrayMap.create(Map.of(Keyword.intern("return"), r)));
            }
        } catch (Throwable e) {
            throw new RuntimeException("signalfd mask update failed", e);
        }
    }

    private static int signo(String signame) {
        Integer n = SIGNOS.get(signame);
        if (n == null) {
            throw new IllegalArgumentException(
                "Unsupported signal for FFM backend: " + signame);
        }
        return n;
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

    @Override
    public synchronized void register(String signame, Seqable runnables) {
        int signo = signo(signame);
        if (!externalAllowlist.isEmpty() && !externalAllowlist.contains(signo)) {
            throw new IllegalArgumentException(
                "signal " + signame + " was not pre-blocked by the launcher's allowlist");
        }
        boolean firstRegistration = !registry.containsKey(signo);
        registry.put(signo, runnables);
        // HotSpot uses SIGUSR2 internally for suspend/resume. Its disposition
        // is process-wide, so replacing it with SIG_DFL would let an unrelated
        // VM operation terminate the JVM. The dispatcher still blocks SIGUSR2
        // and consumes its own thread-directed raises through signalfd.
        if (signo != HOTSPOT_SIGNAL) {
            // SIG_IGN is only safe when every thread has this signal blocked
            // (the launcher's allowlist), since disposition is process-wide but
            // the mask is per-thread. Outside the allowlist, only the
            // dispatcher thread blocks it, so SIG_DFL preserves the historical
            // (racy but not silent) termination behavior elsewhere.
            long previous = setDisposition(signo,
                externalAllowlist.contains(signo) ? SIG_IGN : SIG_DFL);
            if (firstRegistration) previousDispositions.put(signo, previous);
        }
        rebuildMask();
    }

    @Override
    public synchronized void reset(String signame) {
        int signo = signo(signame);
        registry.remove(signo);
        rebuildMask();
        if (signo != HOTSPOT_SIGNAL) {
            setDisposition(signo, previousDispositions.getOrDefault(signo, SIG_DFL));
            previousDispositions.remove(signo);
        }
    }

    @Override
    public synchronized void resetAll() {
        ArrayList<Integer> signos = new ArrayList<>(registry.keySet());
        registry.clear();
        rebuildMask();
        for (Integer signo : signos) {
            if (signo != HOTSPOT_SIGNAL) {
                setDisposition(signo, previousDispositions.getOrDefault(signo, SIG_DFL));
                previousDispositions.remove(signo);
            }
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
        // Serialize raises and wait for the dispatcher to fold the resulting
        // read, so a raise's handlers are observably finished before raise()
        // returns and signals cannot bleed into a later caller's handler set.
        synchronized (raiseLock) {
            CountDownLatch done = new CountDownLatch(1);
            raiseDone = done;
            try {
                // Direct the signal at the dispatcher thread (where it is
                // blocked), so it becomes pending there and is read from the fd.
                int r = (int) pthreadKill.invokeExact(dispatcherPthread, signo);
                requireZero("pthread_kill", r);
                long n = writeWakeup(wakeFd);
                if (n != 8L) throw new IllegalStateException("write() return=" + n);
            } catch (Throwable e) {
                raiseDone = null;
                throw new RuntimeException("pthread_kill failed for " + signame, e);
            }
            try {
                done.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            raiseDone = null;
        }
    }
}
