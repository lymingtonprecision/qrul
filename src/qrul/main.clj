(ns qrul.main
  (:require [clojure.string :as string]
            [com.stuartsierra.component :as component]
            [environ.core :refer [env]]
            [qrul.logging]
            [qrul.core :refer [system]])
  (:gen-class))

(defn env-config []
  {:port (some-> (:qrul-port env) Integer/parseInt)
   :broker-list (:kafka-brokers env)
   :topic (:topic env)})

(defn -main [& args]
  (let [sys (component/start (system (env-config)))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(component/stop sys)))
    (while (some? (some-> sys :tcp-listener :instance))
      (Thread/sleep 5000))))
