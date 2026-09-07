(ns beckon-ffm-test
  "Tests beckon's behavior with the FFM backend that this platform selects:
  signalfd on Linux and kqueue on macOS. project.clj sets
  -Dbeckon.signal.backend=ffm and --enable-native-access."
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [beckon :as beckon]
            [beckon-ffm :as beckon-ffm])
  (:import (com.hypirion.beckon SignalRegistererHelper)
           (java.lang.reflect InvocationTargetException Modifier)
           (java.lang.foreign FunctionDescriptor Linker Linker$Option MemoryLayout MemorySegment
                              ValueLayout)))

(def ^:private signal-fn
  (delay
    (let [linker (Linker/nativeLinker)
          libc   (.defaultLookup linker)]
      (.downcallHandle
       linker
       (.orElseThrow (.find libc "signal"))
       (FunctionDescriptor/of
        ValueLayout/ADDRESS
        (into-array MemoryLayout [ValueLayout/JAVA_INT ValueLayout/ADDRESS]))
       (make-array Linker$Option 0)))))

(defn- set-native-disposition [signo handler]
  (-> ^java.lang.invoke.MethodHandle @signal-fn
      (.invokeWithArguments
       (object-array [(int signo) (MemorySegment/ofAddress (long handler))]))
      ^MemorySegment
      (.address)))

(defn- backend-instance []
  (let [backend-class (Class/forName
                       (str "com.hypirion.beckon."
                            (SignalRegistererHelper/backendName)))]
    (.newInstance (.getDeclaredConstructor backend-class
                                             (make-array Class 0))
                  (object-array 0))))

(defn- dispatcher-thread [backend]
  (let [field (.getDeclaredField (.getClass backend) "dispatcherThread")]
    (.setAccessible field true)
    (.get field backend)))

(defn- native-descriptor [backend]
  (let [field-name (if (= "FfmKqueueBackend" (SignalRegistererHelper/backendName))
                     "kq"
                     "fd")
        field (.getDeclaredField (.getClass backend) field-name)]
    (.setAccessible field true)
    (.get field backend)))

(defn- backend-signal-names []
  (let [backend-class (Class/forName
                       (str "com.hypirion.beckon."
                            (SignalRegistererHelper/backendName)))
        field (.getDeclaredField backend-class "SIGNOS")]
    (.setAccessible field true)
    (set (keys (.get field nil)))))

(defn- linux? [] (= "Linux" (System/getProperty "os.name")))
(defn- macos? [] (= "Mac OS X" (System/getProperty "os.name")))

(defn- platform-test [supported? reason f]
  (if supported?
    (f)
    (do
      (println "SKIPPED:" reason)
      (is (= :skipped :skipped) reason))))

(defn- native-handle [backend field-name]
  (let [field (.getDeclaredField (.getClass backend) field-name)]
    (.setAccessible field true)
    (.get field backend)))

(defn- invoke-static [class-name method-name args]
  (let [klass (Class/forName class-name)
        method (.getDeclaredMethod klass method-name
                                   (into-array Class
                                               (map #(if (integer? %)
                                                       Integer/TYPE
                                                       (class %))
                                                    args)))]
    (.setAccessible method true)
    (try
      (.invoke method nil (object-array (map #(if (integer? %) (int %) %)
                                             args)))
      (catch InvocationTargetException e
        (.getMessage (.getCause e))))))

(use-fixtures :each (fn [run] (try (run) (finally (beckon/reinit-all!)))))

(deftest ffm-backend-is-active
  (testing "this platform loads an FFM backend"
    (is (contains? #{"FfmSignalfdBackend" "FfmKqueueBackend"}
                   (SignalRegistererHelper/backendName)))))

(deftest ffm-backends-implement-supported-signals-as-instance-methods
  (doseq [class-name ["com.hypirion.beckon.FfmKqueueBackend"
                      "com.hypirion.beckon.FfmSignalfdBackend"]]
    (let [method (.getMethod (Class/forName class-name) "supportedSignals"
                             (make-array Class 0))]
      (is (not (Modifier/isStatic (.getModifiers method)))
          (str class-name ".supportedSignals() must implement SignalBackend")))))

(deftest capabilities-are-side-effect-free-and-actionable
  (let [capability-fn (ns-resolve 'beckon-ffm 'capabilities)]
    (is (ifn? capability-fn))
    (let [capabilities (when capability-fn (capability-fn))]
    (is (= (System/getProperty "os.name") (:os capabilities)))
    (is (= (System/getProperty "os.arch") (:architecture capabilities)))
    (is (= (SignalRegistererHelper/backendName) (:backend capabilities)))
    (is (seq (:supported-signals capabilities)))
    (is (seq (:required-symbols capabilities)))
    (is (integer? (:java-major-version capabilities)))
    (is (boolean? (:native-access-enabled? capabilities)))
      (is (map? (:external-signal-prerequisites capabilities))))))

(deftest backend-signal-map-covers-catchable-platform-signals
  (let [expected (if (= "FfmSignalfdBackend" (SignalRegistererHelper/backendName))
                   #{"HUP" "INT" "QUIT" "USR1" "TERM" "CHLD" "CONT"
                     "TSTP" "WINCH" "ALRM" "TTIN" "TTOU" "URG" "XCPU"
                     "VTALRM" "PROF" "IO" "PWR"}
                   #{"HUP" "INT" "QUIT" "ILL" "TRAP" "ABRT" "EMT"
                     "FPE" "BUS" "SEGV" "SYS" "PIPE" "ALRM" "TERM"
                     "URG" "TSTP" "CONT" "CHLD" "TTIN" "TTOU" "IO"
                     "XCPU" "XFSZ" "VTALRM" "PROF" "WINCH" "INFO" "USR1"})]
    (is (= expected (backend-signal-names))
        "the backend map must include every safe, catchable platform signal")))

(deftest linux-abi-validation-reports-the-actual-size
  (testing "a Linux ABI size mismatch names the struct, expected size, actual size, and platform"
    (let [message (invoke-static "com.hypirion.beckon.LinuxAbi" "validate"
                                ["Linux" "x86_64" 64 128 8])]
      (is (= "expected sigset_t of 128 bytes, got 64 on Linux/x86_64"
             message)))))

(deftest native-signal-failure-surfaces-return-and-errno
  (testing "a failed signal(2) disposition change is not silently accepted"
    (let [backend-class (Class/forName
                         (str "com.hypirion.beckon."
                              (SignalRegistererHelper/backendName)))
          backend       (.newInstance (.getDeclaredConstructor backend-class
                                                               (make-array Class 0))
                                      (object-array 0))
          set-disposition (.getDeclaredMethod backend-class "setDisposition"
                                              (into-array Class [Integer/TYPE Long/TYPE]))]
      (.setAccessible set-disposition true)
      (try
        (.invoke set-disposition backend (object-array [(int 0) (long 0)]))
        (is false "signal(0, SIG_DFL) should throw instead of returning SIG_ERR")
        (catch InvocationTargetException e
          (let [failure (.getCause e)]
            (is (instance? clojure.lang.ExceptionInfo failure))
            (is (= -1 (:return (ex-data failure))))
            (is (integer? (:errno (ex-data failure))))))))))

(deftest signal-atom-identity
  (testing "the same signal name gives the same atom"
    (is (identical? (beckon/signal-atom "USR1") (beckon/signal-atom "USR1"))))
  (testing "different signals give different atoms"
    (is (not (identical? (beckon/signal-atom "USR1") (beckon/signal-atom "WINCH"))))))

(deftest handler-runs-on-raise
  (testing "a handler in the atom runs when the signal is raised"
    (let [ran (promise)]
      (reset! (beckon/signal-atom "USR1") [(fn [] (deliver ran true))])
      (beckon/raise! "USR1")
      (is (true? (deref ran 2000 :timed-out))))))

(deftest all-handlers-run
  (testing "one raise invokes every Runnable in the collection"
    (let [hits  (atom 0)
          three (java.util.concurrent.CountDownLatch. 3)
          bump  (fn [] (swap! hits inc) (.countDown three))]
      (reset! (beckon/signal-atom "USR1") [bump bump bump])
      (beckon/raise! "USR1")
      (is (.await three 2 java.util.concurrent.TimeUnit/SECONDS))
      (is (= 3 @hits)))))

(deftest empty-handler-collection-is-a-noop
  (testing "a raise with no handlers does not throw"
    (reset! (beckon/signal-atom "USR1") [])
    (is (nil? (beckon/raise! "USR1")))))

(deftest reset-restores-prior-native-disposition
  (testing "reset restores the disposition installed before registration"
    (let [signo (if (= "FfmKqueueBackend" (SignalRegistererHelper/backendName)) 30 10)
          prior (set-native-disposition signo 1)]
      (try
        (reset! (beckon/signal-atom "USR1") [identity])
        (beckon/reinit-all!)
        (is (= 1 (set-native-disposition signo 1)))
        (finally
          (set-native-disposition signo prior))))))

(deftest registration-does-not-install-default-disposition
  (let [expected (if (= "FfmSignalfdBackend" (SignalRegistererHelper/backendName)) 0 1)
        prior (set-native-disposition 15 1)]
    (try
      (reset! (beckon/signal-atom "TERM") [identity])
      (is (= expected (set-native-disposition 15 expected))
          "managed registration must use the backend's safe default disposition")
      (finally
        (set-native-disposition 15 prior)))))

(deftest jvm-reserved-signal-is-rejected
  (let [backend (backend-instance)]
    (try
      (is (thrown-with-msg? IllegalArgumentException
                            #"Unsupported signal.*USR2"
                            (.register backend "USR2" [identity])))
      (finally
        (beckon-ffm/close! backend)))))

(deftest backend-close-stops-dispatcher-restores-dispositions-and-releases-resources
  (let [signo (if (= "FfmKqueueBackend" (SignalRegistererHelper/backendName)) 30 10)
        prior (set-native-disposition signo 1)
        backend (backend-instance)]
    (try
      (.register backend "USR1" [identity])
      (beckon-ffm/close! backend)
      (.join (dispatcher-thread backend) 2000)
      (is (not (.isAlive (dispatcher-thread backend)))
          "close must terminate the dispatcher thread")
      (is (= -1 (native-descriptor backend))
          "close must invalidate the native descriptor")
      (when (= "FfmSignalfdBackend" (SignalRegistererHelper/backendName))
        (let [field (.getDeclaredField (.getClass backend) "wakeFd")]
          (.setAccessible field true)
          (is (= -1 (.get field backend))
              "close must invalidate the signalfd wake descriptor")))
      (is (= 1 (set-native-disposition signo 1))
          "close must restore the disposition from before registration")
      (is (nil? (beckon-ffm/close! backend)) "close must be idempotent")
      (finally
        (set-native-disposition signo prior)))))

(deftest backend-can-be-reinitialized-after-close
  (let [backend (backend-instance)
        fresh (backend-instance)
        ran (promise)]
    (try
      (beckon-ffm/close! backend)
      (.register fresh "USR1" [(fn [] (deliver ran true))])
      (.raise fresh "USR1")
      (is (true? (deref ran 2000 :timed-out)))
      (finally
        (beckon-ffm/close! backend)
        (beckon-ffm/close! fresh)))))

(deftest concurrent-register-and-reset-is-serialized
  (let [backend (backend-instance)
        signals ["ALRM" "TTIN" "TTOU" "URG"]]
    (try
      (let [jobs (doall (map (fn [signal]
                              (future
                                (dotimes [_ 12]
                                  (.register backend signal [identity])
                                  (.reset backend signal))))
                            signals))]
        (doseq [job jobs]
          (is (nil? (deref job 5000 ::timeout))
              "concurrent register/reset must not deadlock")))
      (doseq [signal signals]
        (is (empty? (seq (.currentRunnables backend signal)))))
      (finally
        (beckon-ffm/close! backend)))))

(deftest repeated-construction-releases-each-dispatcher
  (dotimes [_ 8]
    (let [backend (backend-instance)
          thread (dispatcher-thread backend)]
      (try
        (.register backend "USR1" [identity])
        (finally
          (beckon-ffm/close! backend)))
      (is (not (.isAlive thread))
          "every constructed backend must terminate its dispatcher"))))

(deftest signalfd-dispatcher-survives-repeated-thread-directed-raises
  (platform-test (= "FfmSignalfdBackend" (SignalRegistererHelper/backendName))
                 "signalfd-only regression test requires Linux; covered by Linux CI"
                 (fn []
                   (let [backend (backend-instance)
                         hits (atom 0)]
                     (try
                       (.register backend "USR1" [(fn [] (swap! hits inc))])
                       (dotimes [_ 8]
                         (.raise backend "USR1"))
                       (is (= 8 @hits)
                           "every thread-directed signal must be consumed by signalfd")
                       (is (.isAlive (dispatcher-thread backend))
                           "the poll-based dispatcher must remain alive after a raise")
                       (finally
                         (beckon-ffm/close! backend))))))

#_{:clj-kondo/ignore [:inline-def]}
(deftest unsupported-backend-startup-fails-before-dispatch
  (let [opposite (if (linux?) "FfmKqueueBackend" "FfmSignalfdBackend")
        constructor (.getDeclaredConstructor
                     (Class/forName (str "com.hypirion.beckon." opposite))
                     (make-array Class 0))]
    (is (thrown-with-msg? UnsupportedOperationException
                          #"requires (Linux|macOS/BSD)"
                          (.newInstance constructor (object-array 0)))
        "an unsupported backend must fail during startup, not leave a thread behind")))

#_{:clj-kondo/ignore [:inline-def]}
(deftest selected-backend-native-error-is-immediate
  (let [backend (backend-instance)]
    (try
      (if (= "FfmKqueueBackend" (SignalRegistererHelper/backendName))
        (let [kevent (native-handle backend "kevent")]
          (is (= -1 (long (.invokeWithArguments
                           ^java.lang.invoke.MethodHandle kevent
                           (object-array [-1 java.lang.foreign.MemorySegment/NULL 0
                                          java.lang.foreign.MemorySegment/NULL 0
                                          java.lang.foreign.MemorySegment/NULL]))))
              "kevent on an invalid descriptor must report its native error"))
        (let [read (native-handle backend "read")
              arena (java.lang.foreign.Arena/ofConfined)]
          (try
            (is (= -1 (long (.invokeWithArguments
                             ^java.lang.invoke.MethodHandle read
                             (object-array [-1 (.allocate arena 128) (long 128)]))))
                "read on an invalid descriptor must report its native error")
            (finally (.close arena)))))
      (finally
        (beckon-ffm/close! backend))))))

(deftest signalfd-close-releases-both-descriptors
  (platform-test (linux?)
                 "FfmSignalfdBackend requires Linux; covered by Linux CI"
                 (fn []
                   (let [backend (backend-instance)
                         thread (dispatcher-thread backend)]
                     (try
                       (.register backend "USR1" [identity])
                       (finally (beckon-ffm/close! backend)))
                     (.join thread 2000)
                     (is (not (.isAlive thread)))
                     (is (= -1 (native-descriptor backend)))
                     (is (= -1 (native-handle backend "wakeFd")))))))

(defn- launcher-path []
  (str (System/getProperty "user.dir") "/target/beckon-signal-launcher"))

(defn- child-command []
  ["java" "-Xrs" "--enable-native-access=ALL-UNNAMED"
   "-Dbeckon.signal.backend=ffm"
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "beckon-signal-child" "TERM"])

(defn- wait-for-line
  "Reads lines from reader until expected is seen, EOF, or timeout-ms elapses.
  Runs the blocking read on a daemon future so a hung subprocess can never
  wedge the test suite indefinitely."
  [^java.io.BufferedReader reader expected timeout-ms]
  (let [f (future
            (loop [line (.readLine reader)]
              (cond
                (= line expected) true
                (nil? line) false
                :else (recur (.readLine reader)))))
        result (deref f timeout-ms ::timeout)]
    (if (= result ::timeout)
      (do (future-cancel f) false)
      result)))

(deftest external-term-works-through-prelaunch-shim
  (platform-test (linux?)
                 "Linux launcher subprocess test; covered by Linux CI"
                 (fn []
                   (let [process (-> (ProcessBuilder.
                                      (into-array String
                                                  (concat [(launcher-path) "--signals" "TERM" "--"]
                                                          (child-command))))
                                     (.redirectErrorStream true)
                                     (.start))
                         reader (io/reader (.getInputStream process))]
                     (try
                       (is (wait-for-line reader "READY" 5000))
                       (.destroy (.orElseThrow (java.lang.ProcessHandle/of (.pid process))))
                       (is (wait-for-line reader "HANDLED" 5000))
                       (is (.isAlive process))
                       (finally
                         (.destroyForcibly process)))))))

(deftest external-term-works-on-kqueue
  (platform-test (macos?)
                 "macOS kqueue subprocess test; covered by macOS CI"
                 (fn []
                   (let [process (-> (ProcessBuilder. (into-array String (child-command)))
                                     (.redirectErrorStream true)
                                     (.start))
                         reader (io/reader (.getInputStream process))]
                     (try
                       (is (wait-for-line reader "READY" 5000))
                       (let [killer (-> (ProcessBuilder.
                                         (into-array String ["kill" "-TERM" (str (.pid process))]))
                                        (.start))]
                         (.waitFor killer 5 java.util.concurrent.TimeUnit/SECONDS))
                       (is (wait-for-line reader "HANDLED" 5000))
                       (is (.isAlive process)
                           "kqueue must handle an external TERM without terminating the child")
                       (finally
                         (.destroyForcibly process)))))))

(deftest external-term-without-shim-retains-known-limitation
  (platform-test (linux?)
                 "Linux no-shim subprocess test; covered by Linux CI"
                 (fn []
                   (let [process (-> (ProcessBuilder.
                                      (into-array String (child-command)))
                                     (.redirectErrorStream true)
                                     (.start))
                         reader (io/reader (.getInputStream process))]
                     (try
                       (is (wait-for-line reader "READY" 5000))
                       (.destroy (.orElseThrow (java.lang.ProcessHandle/of (.pid process))))
                       (let [exited? (.waitFor process 15 java.util.concurrent.TimeUnit/SECONDS)]
                         (is exited? "process should exit under the default (unmasked) disposition")
                         (when exited? (is (not= 0 (.exitValue process)))))
                       (finally
                         (.destroyForcibly process)))))))
