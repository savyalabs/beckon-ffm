(ns beckon-ffm
  "Lifecycle helpers for beckon-ffm's opt-in native signal backend.")

(defn- current-backend []
  (let [field (.getDeclaredField com.hypirion.beckon.SignalRegistererHelper
                                  "BACKEND")]
    (.setAccessible field true)
    (.get field nil)))

(def ^:private required-symbols
  {"FfmSignalfdBackend"
   ["signalfd" "read" "sigemptyset" "sigaddset" "pthread_sigmask"
    "pthread_self" "pthread_kill" "signal" "eventfd" "poll" "write" "close"]
   "FfmKqueueBackend"
   ["kqueue" "kevent" "signal" "kill" "getpid" "close"]})

(defn- backend-name []
  (com.hypirion.beckon.SignalRegistererHelper/backendName))

(defn- native-access-enabled? []
  (boolean (some #(re-find #"--enable-native-access=(ALL-UNNAMED|.*beckon.*)" %)
                (.getInputArguments (java.lang.management.ManagementFactory/getRuntimeMXBean)))))

(defn capabilities
  "Return side-effect-free information needed before selecting the FFM backend."
  []
  (let [backend (backend-name)
        klass (Class/forName (str "com.hypirion.beckon." backend))
        method (.getMethod klass "staticSupportedSignals" (make-array Class 0))
        version (first (re-find #"^(\d+)" (System/getProperty "java.version")))]
    {:os (System/getProperty "os.name")
     :architecture (System/getProperty "os.arch")
     :backend backend
     :supported-signals (set (.invoke method nil (object-array 0)))
     :required-symbols (get required-symbols backend [])
     :java-major-version (Integer/parseInt version)
     :native-access-enabled? (native-access-enabled?)
     :external-signal-prerequisites
     (if (= backend "FfmSignalfdBackend")
       {:requires-prelaunch-shim? true :requires-xrs? true :requires-sigblk? true}
       {:requires-prelaunch-shim? false :requires-xrs? false :requires-sigblk? false})}))

(defn close!
  "Stops and closes the current beckon backend.

  Use this before REPL reloads, test-suite teardown, or supervised process
  restarts when the JVM will continue running. An optional backend argument is
  useful when managing a backend constructed directly from Java."
  ([] (close! (current-backend)))
  ([backend]
   (clojure.lang.Reflector/invokeInstanceMethod backend "close"
                                                (object-array 0))))
