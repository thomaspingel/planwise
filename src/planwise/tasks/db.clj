(ns planwise.tasks.db
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [taoensso.timbre :as timbre]
            [com.stuartsierra.component :as component]
            [duct.component.ragtime :refer [ragtime migrate rollback]]
            [duct.component.hikaricp :refer [hikaricp]]
            [meta-merge.core :refer [meta-merge]]
            [planwise.config :as config])
  (:gen-class))


(def config
  (meta-merge config/defaults
              config/environ))

(defn new-system [config]
  (-> (component/system-map
       :db         (hikaricp (:db config))
       :ragtime    (ragtime {:resource-path "migrations"}))
      (component/system-using
        {:ragtime  [:db]})))

(defn load-sql-functions
  [system]
  (let [sql-source (-> (io/resource "planwise/plpgsql/functions.sql")
                       slurp)]
    (println "Loading SQL functions into database")
    (jdbc/execute! (:spec (:db system)) sql-source)))

(defn -main [& args]
  (timbre/set-level! :warn)
  (if-let [cmd (first args)]
    (let [system (new-system config)
          system (component/start system)]
      (case cmd
        "migrate" (migrate (:ragtime system))
        "rollback" (if-let [target (second args)]
                     (rollback (:ragtime system) target)
                     (rollback (:ragtime system))))
      (load-sql-functions system))
    (println "Run DB command with either migrate or rollback")))
