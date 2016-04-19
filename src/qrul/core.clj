(ns qrul.core
  (:require [clojure.string :as string]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as log]
            [clj-time.coerce :as time.coerce]
            [clj-time.format :as time.format]

            ;; tcp server
            [aleph.tcp :as tcp]
            [byte-streams :as bytes]
            [manifold.stream :as stream]

            ;; kafka publisher
            [franzy.admin.zookeeper.client :refer [make-zk-utils]]
            [franzy.admin.configuration]
            [franzy.admin.topics :as topics]
            [franzy.admin.cluster :as zk-cluster]
            [franzy.clients.producer.client :refer [make-producer]]
            [franzy.clients.producer.defaults :refer [make-default-producer-options]]
            [franzy.clients.producer.protocols :as producer]
            [franzy.serialization.json.serializers :refer [json-serializer]]))

(def ^:dynamic *default-port* 13478)
(def ^:dynamic *default-kafka-topic* "quick-report-usage")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; app protocols

(defprotocol QrUsageLogger
  (log-qr-usage! [this qr-id user-id ts]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; timestamp formatting

(def iso8601 (time.format/formatters :basic-date-time))

(defn date->iso8601 [d]
  (time.format/unparse iso8601 (time.coerce/from-date d)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tcp server

(def message-format
  #"^([1-9]\d*):([A-Z]+):([1-9]\d{12})$")

(defn parse-message
  [s]
  (when-let [[_ id user ts] (re-matches message-format s)]
    {:qr (Integer/parseInt id)
     :user user
     :ts (java.util.Date. (Long/parseLong ts))}))

(defn parse-packet
  [s]
  (parse-message (string/trim (bytes/to-string s))))

(defn handler
  [usage-logger conn-info]
  (fn [packet]
    (if-let [{:keys [qr user ts]} (parse-packet packet)]
      (log-qr-usage! usage-logger qr user ts)
      (log/error "bad packet from" (:remote-addr conn-info)
                 (bytes/to-string packet)))))

(defn server
  [port usage-logger]
  (let [s (tcp/start-server
           (fn [s info]
             (log/debug "connection from" (:remote-addr info))
             (stream/consume (handler usage-logger info) s))
           {:port port})]
    (log/info (str "QRUL listening on 0.0.0.0:" port))
    s))

(defrecord TcpUsageListener [port usage-logger]
  component/Lifecycle
  (start [this]
    (if (:instance this)
      this
      (assoc this :instance (server port usage-logger))))
  (stop [this]
    (when-let [i (:instance this)] (.close i))
    (assoc this :instance nil)))

(defn tcp-listener
  [port]
  (map->TcpUsageListener {:port port}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; kafka

(def topic-config
  {"cleanup.policy" "delete"
   "retention.ms" -1
   "retention.bytes" -1})

(defn create-topic!
  [zk-client topic]
  (when-not (topics/topic-exists? zk-client topic)
    (topics/create-topic!
     zk-client
     topic
     1
     (count (zk-cluster/broker-ids zk-client))
     topic-config)))

(defrecord KafkaUsageLogger [config zk-config topic]
  component/Lifecycle
  (start [this]
    (if (:instance this)
      this
      (do
        (with-open [zk (make-zk-utils zk-config false)]
          (create-topic! zk topic))
        (let [o (make-default-producer-options)
              p (make-producer
                 config
                 (json-serializer)
                 (json-serializer)
                 o)]
          (assoc this :instance p :options o)))))
  (stop [this]
    (when-let [p (:instance this)] (.close p))
    (assoc this :instance nil))

  QrUsageLogger
  (log-qr-usage! [this qr-id user-id ts]
    (let [ur {:quick-report qr-id :user-id user-id :timestamp (date->iso8601 ts)}]
      (log/debug "logging usage" ur)
      (if-let [p (:instance this)]
        (producer/send-async! p topic 0 nil ur (:options this))
        (log/warn "can't log usage: no Kafka producer instance")))))

(defn kafka-usage-logger
  [broker-list zk-servers]
  (->KafkaUsageLogger
   {:bootstrap.servers broker-list}
   {:servers zk-servers}
   *default-kafka-topic*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; system

(defn system
  [{:keys [port broker-list zk-servers]}]
  (component/system-using
   (component/system-map
    :tcp-listener (tcp-listener (or port *default-port*))
    :kafka-logger (kafka-usage-logger broker-list zk-servers))
   {:tcp-listener {:usage-logger :kafka-logger}}))
