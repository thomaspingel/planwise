(ns planwise.client.scenarios.db
  (:require [planwise.client.asdf :as asdf]))

(def initial-db
  {:view-state :current-scenario
   :rename-dialog            nil
   :current-scenario         nil
   :changeset-dialog         nil
   :list-scope               nil
   :list                     (asdf/new nil)})


(defmulti new-action :action-name)

(defmethod new-action :create
  [props]
  {:action     "create-provider"
   :name       (:name props)
   :investment 0
   :capacity   0
   :location   (:location props)
   :id         (str (random-uuid))})

(defmethod new-action :upgrade
  [props]
  {:action     "upgrade-provider"
   :investment 0
   :capacity   0
   :id         (:id props)})

(defmethod new-action :increase
  [props]
  {:action     "increase-provider"
   :investment 0
   :capacity   0
   :id         (:id props)})

(defn new-provider-from-change
  [change]
  {:id             (:id change)
   :name           (:name change)
   :location       (:location change)
   :matches-filter true
   :change         change})
