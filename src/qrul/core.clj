(ns qrul.core
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [cheshire.core :as cheshire]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as log]
            [clj-time.coerce :as time.coerce]
            [clj-time.format :as time.format]

            ;; tcp server
            [aleph.tcp :as tcp]
            [byte-streams :as bytes]
            [manifold.stream :as stream])
  (:import [java.io ByteArrayOutputStream]
           [org.apache.kafka.clients.producer KafkaProducer ProducerRecord]
           [org.apache.kafka.common.serialization Serializer]))

(def ^:dynamic *default-port* 13478)

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
;; serialization

(defn json-serializer []
  (reify
    Serializer
    (close [this])
    (configure [this configs is-key?])
    (serialize [this topic payload]
      (when payload
        (with-open [ba (ByteArrayOutputStream.)
                    w (io/writer ba)]
          (cheshire/generate-stream payload w)
          (.toByteArray ba))))))

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

(defn ->properties
  [kvs]
  (into {} (for [[k v] kvs] [(name k) (str v)])))

(defn ->record [topic key value]
  (ProducerRecord. topic key value))

(defn kafka-producer [config key-serializer value-serializer]
  (KafkaProducer. (->properties config) key-serializer value-serializer))

(defrecord KafkaUsageLogger [config topic]
  component/Lifecycle
  (start [this]
    (if (:instance this)
      this
      (do
        (let [s (json-serializer)
              p (kafka-producer config s s)]
          (assoc this :instance p)))))
  (stop [this]
    (when-let [p (:instance this)] (.close p))
    (assoc this :instance nil))

  QrUsageLogger
  (log-qr-usage! [this qr-id user-id ts]
    (let [ur {:quick-report qr-id :user-id user-id :timestamp (date->iso8601 ts)}]
      (log/debug "logging usage" ur)
      (if-let [p (:instance this)]
        (.send p (->record topic nil ur))
        (log/warn "can't log usage: no Kafka producer instance")))))

(defn kafka-usage-logger
  [broker-list topic]
  (->KafkaUsageLogger
   {:bootstrap.servers broker-list}
   topic))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; system

(defn system
  [{:keys [port broker-list topic]}]
  (component/system-using
   (component/system-map
    :tcp-listener (tcp-listener (or port *default-port*))
    :kafka-logger (kafka-usage-logger broker-list topic))
   {:tcp-listener {:usage-logger :kafka-logger}}))
