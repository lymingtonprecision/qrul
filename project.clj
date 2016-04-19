(defproject qrul "0.1.0"
  :description "Quick Report Usage Logger"
  :url "https://github.com/lymingtonprecision/qrul"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]

                 ;; system
                 [com.stuartsierra/component "0.3.1"]
                 [clj-time "0.11.0"]
                 [environ "1.0.2"]

                 ;;;; logging
                 ;; use logback as the main Java logging implementation
                 [ch.qos.logback/logback-classic "1.1.6"]
                 [ch.qos.logback/logback-core "1.1.6"]
                 ;; with SLF4J as the main redirect
                 [org.slf4j/slf4j-api "1.7.19"]
                 [org.slf4j/jcl-over-slf4j "1.7.19"]
                 [org.slf4j/log4j-over-slf4j "1.7.19"]
                 [org.apache.logging.log4j/log4j-to-slf4j "2.5"]
                 ;; and timbre for our own logging
                 [com.taoensso/timbre "4.3.1"]

                 ;; server socket
                 [aleph "0.4.1"]

                 ;; kafka
                 [ymilky/franzy "0.0.1" :exclusions [log4j]]
                 [ymilky/franzy-admin "0.0.1" :exclusions [log4j]]
                 [ymilky/franzy-json "0.0.1" :exclusions [log4j]]]

  :main qrul.main

  :jvm-opts ["-Duser.timezone=UTC"]

  :profiles
  {:uberjar {:aot :all
             :uberjar-name "qrul-standalone.jar"}}

  :release-tasks
  [["vcs" "assert-committed"]
   ["change" "version" "leiningen.release/bump-version" "release"]
   ["vcs" "commit"]
   ["vcs" "tag"]
   ["change" "version" "leiningen.release/bump-version"]
   ["vcs" "commit"]
   ["vcs" "push"]])
