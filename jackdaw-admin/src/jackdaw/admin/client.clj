(ns jackdaw.admin.client
  (:require [clojure.tools.logging :as log]
            [jackdaw.client :as jc])
  (:import [org.apache.kafka.clients.admin
            AdminClient
            DescribeTopicsOptions
            DescribeTopicsResult
            NewTopic]
           [org.apache.kafka.common.config
            ConfigResource
            ConfigResource$Type]))

(defn client [kafka-config]
  {:pre [(get kafka-config "bootstrap.servers")]}
  (AdminClient/create (jc/map->properties kafka-config)))

(defn client? [x]
  (instance? AdminClient x))

(defn describe-topics [client names]
  (as-> client %
      (.describeTopics % names (DescribeTopicsOptions.))
      (.all %)
      (deref %)
      (map (fn [[name topic-description]]
             [name {:is-internal? (.isInternal topic-description)
                    :partition-info (map (fn [part-info]
                                           {:isr (.isr part-info)
                                            :leader (.leader part-info)
                                            :partition (.partition part-info)
                                            :replicas (.replicas part-info)})
                                         (.partitions topic-description))}])
           %)
      (into {} %)))

(defn existing-topic-names [client]
  (-> client .listTopics .names deref))

(defn topic-exists? [client name]
  (contains? (existing-topic-names client) name))

(defn create-topic-
  [client topic-name num-partitions replication-factor topic-config]
  (let [topic (NewTopic. topic-name num-partitions replication-factor)]
    (.configs topic (jc/map->properties topic-config))
    (-> client
        (.createTopics [topic])
        .all
        deref))
  (log/info (format "Created topic %s" topic-name)))

(defn create-topics! [client topic-metadata]
  (doseq [{topic-name :jackdaw.topic/topic-name
           partitions :jackdaw.topic/partitions
           replication-factor :jackdaw.topic/replication-factor
           topic-config :jackdaw.topic/topic-config} topic-metadata
          :when (not (topic-exists? client topic-name))]
    (create-topic- client
                   topic-name
                   (int partitions)
                   (int replication-factor)
                   topic-config)))

(defn topics-ready?
  "Waits until all topics and partitions are ready (have a leader, in-sync-replicas)"
  [client names]
  (->> (describe-topics client names)
       (every? (fn [topic]
                 (every? (fn [part-info]
                           (and (boolean (:leader part-info))
                                (seq (:isr part-info))))
                         (:partition-info topic))))))



(defn node->map [node]
  {:host (.host node)
   :port (.port node)
   :id (.id node)
   :rack (.rack node)})

(defn describe-cluster [client]
  (let [result (-> client
                   (.describeCluster))]
    {:cluster-id (-> result .clusterId .get)
     :controller (-> result .controller .get)
     :nodes (-> result .nodes .get (->> (map node->map) vec))}))

(defn config->map [config]
  (-> config
      .entries
      seq
      (->>
       (map (fn [e]
              [(.name e) {:value (.value e)
                          :default? (.isDefault e)
                          :read-only? (.isReadOnly e)
                          :sensitive? (.isSensitive e)}]))
       (into {}))))

(defn get-broker-config
  "Returns the broker config as a map.

  Broker-id is an int, typically 0-2, get the list of valid broker ids
  using describe-cluster"
  [client broker-id]
  {:pre [(client? client)]}
  (-> client
      (.describeConfigs [(ConfigResource. ConfigResource$Type/BROKER (str broker-id))])
      .all
      .get
      vals
      first
      config->map))
