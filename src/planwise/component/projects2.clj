(ns planwise.component.projects2
  (:require [planwise.boundary.projects2 :as boundary]
            [planwise.boundary.providers-set :as providers-set]
            [planwise.boundary.scenarios :as scenarios]
            [planwise.component.regions :as regions]
            [integrant.core :as ig]
            [taoensso.timbre :as timbre]
            [clojure.java.jdbc :as jdbc]
            [hugsql.core :as hugsql]
            [clojure.edn :as edn]
            [clojure.string :refer [join]]
            [clojure.java.io :as io]
            [planwise.model.projects2 :as model]
            [planwise.util.hash :refer [update*]]))

;; ----------------------------------------------------------------------
;; Auxiliary and utility functions

(hugsql/def-db-fns "planwise/sql/projects2.sql")

(defn get-db
  [store]
  (get-in store [:db :spec]))

;; ----------------------------------------------------------------------
;; Service definition

(defn create-project
  [store owner-id]
  (db-create-project! (get-db store) {:owner-id owner-id
                                      :name ""
                                      :state "draft"}))

(defn update-project
  [store project]
  (db-update-project (get-db store) (-> project
                                        (update :config pr-str)
                                        (dissoc :state))))

(defn get-project
  [store project-id]
  (let [{:keys [config provider-set-id region-id] :as project} (db-get-project (get-db store) {:id project-id})
        tags    (when (some? config) (get-in (read-string config) [:providers :tags]))
        number-of-providers (providers-set/count-providers-filter-by-tags (:providers-set store) provider-set-id region-id tags)]
    (regions/db->region
     (-> project
         (update* :engine-config edn/read-string)
         (update* :config edn/read-string)
         (update* :config model/apply-default)
         (assoc   :providers number-of-providers)))))

(defn list-projects
  [store owner-id]
  (db-list-projects (get-db store) {:owner-id owner-id}))

(defn start-project
  [store project-id]
  (db-start-project! (get-db store) {:id project-id})
  (let [project (get-project store project-id)]
    (scenarios/create-initial-scenario (:scenarios store) project)))

(defn reset-project
  [store project-id]
  (db-reset-project! (get-db store) {:id project-id})
  (scenarios/reset-scenarios (:scenarios store) project-id))
  ;; TODO should increment some incarnation to dismiss ongoing deferred jobs

(defn delete-project
  [store project-id]
  (db-delete-project! (get-db store) {:id project-id}))

(defrecord ProjectsStore [db scenarios providers-set]
  boundary/Projects2
  (create-project [store owner-id]
    (create-project store owner-id))
  (list-projects [store owner-id]
    (list-projects store owner-id))
  (get-project [store project-id]
    (get-project store project-id))
  (update-project [store project]
    (update-project store project))
  (start-project [store project-id]
    (start-project store project-id))
  (reset-project [store project-id]
    (reset-project store project-id))
  (delete-project [store project-id]
    (delete-project store project-id)))

(defmethod ig/init-key :planwise.component/projects2
  [_ config]
  (map->ProjectsStore config))

(comment
  ;; REPL testing

  (def store (:planwise.component/projects2 integrant.repl.state/system))
  (start-project store 5))
