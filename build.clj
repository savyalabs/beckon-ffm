(ns build
  "Build + Clojars deploy for beckon-ffm (tools.build + deps-deploy).
   FFM backends are Java-only and require JDK 22+ (java.lang.foreign is final in 22).

   Usage:
     clojure -T:build compile-java   ; javac src/java -> target/classes (JDK 22+)
     clojure -T:build compile-native-shim ; cc tools/beckon-signal-launcher.c -> target/beckon-signal-launcher
     clojure -T:build jar
     clojure -T:build deploy"
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'net.clojars.savya/beckon-ffm)
(def version "0.7.0")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"})
  (b/delete {:path "pom.xml"}))

(defn compile-java [_]
  (b/javac {:src-dirs ["src/java"]
            :class-dir class-dir
            :basis @basis
            :javac-opts ["-source" "22" "-target" "22"]}))

(defn compile-native-shim [_]
  (b/process {:command-args ["cc" "-std=c11" "-Wall" "-Wextra" "-O2"
                             "tools/beckon-signal-launcher.c"
                             "-o" "target/beckon-signal-launcher"]}))

(defn jar [_]
  (clean nil)
  (compile-java nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src/java"]
                :scm {:url "https://github.com/savyalabs/beckon-ffm"
                      :connection "scm:git:https://github.com/savyalabs/beckon-ffm.git"
                      :developerConnection "scm:git:ssh://git@github.com/savyalabs/beckon-ffm.git"
                      :tag (str "v" version)}
                :pom-data [[:description "Experimental Foreign Function & Memory signal backends for beckon: Linux signalfd and macOS/BSD kqueue."]
                           [:url "https://github.com/savyalabs/beckon-ffm"]
                           [:licenses
                            [:license
                             [:name "Eclipse Public License 1.0"]
                             [:url "https://www.eclipse.org/legal/epl-v10.html"]
                             [:distribution "repo"]]]]})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "Wrote" jar-file))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
