(defproject qrul "0.2.0"
  :description "Quick Report Usage Logger"
  :url "https://github.com/lymingtonprecision/qrul"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]

                 ;; system
                 [com.stuartsierra/component "0.3.2"]
                 [clj-time "0.13.0"]
                 [environ "1.1.0"]

                 ;;;; logging
                 ;; use logback as the main Java logging implementation
                 [ch.qos.logback/logback-classic "1.2.2"]
                 [ch.qos.logback/logback-core "1.2.2"]
                 ;; with SLF4J as the main redirect
                 [org.slf4j/slf4j-api "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [org.apache.logging.log4j/log4j-to-slf4j "2.8.1"]
                 ;; and timbre for our own logging
                 [com.taoensso/timbre "4.8.0"]

                 ;; server socket
                 [aleph "0.4.3"]

                 ;; serialization
                 [cheshire "5.7.0"]

                 ;; kafka
                 [org.apache.kafka/kafka-clients "0.10.2.0"]]

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
