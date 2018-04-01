(ns planwise.client.projects2.handlers
  (:require [re-frame.core :refer [register-handler subscribe dispatch] :as rf]
            [planwise.client.asdf :as asdf]
            [planwise.client.projects2.api :as api]
            [planwise.client.routes :as routes]
            [planwise.client.effects :as effects]
            [planwise.client.projects2.db :as db]))

(def in-projects2 (rf/path [:projects2]))

;;------------------------------------------------------------------------------
;; Creating New Project

(rf/reg-event-fx
 :projects2/new-project
 in-projects2
 (fn [_ [_]]
   {:api (assoc (api/create-project!)
                :on-success [:projects2/project-created])}))

(rf/reg-event-fx
 :projects2/project-created
 in-projects2
 (fn [{:keys [db]} [_ project]]
   (let [project-id          (:id project)
         {:keys [id name]}   project
         new-list            (cons {:id id :name name} (:list db))]
     {:db        (-> db
                     (assoc :current-project nil)
                     (assoc :list new-list))
      :navigate  (routes/projects2-show {:id project-id})})))

;;------------------------------------------------------------------------------
;; Updating db

(rf/reg-event-db
 :projects2/save-project-data
 in-projects2
 (fn [db [_ current-project]]
   (assoc db :current-project current-project)))

(rf/reg-event-fx
 :projects2/project-not-found
 in-projects2
 (fn [_ _]
   {:navigate (routes/projects2)}))

(rf/reg-event-fx
 :projects2/get-project-data
 in-projects2
 (fn [{:keys [db]} [_ id]]
   {:api (assoc (api/get-project id)
                :on-success [:projects2/save-project-data]
                :on-failure [:projects2/project-not-found])}))

(rf/reg-event-fx
  :projects2/current-save
  (fn [{:keys [db]} [_ path]]
    (println path)
    (let [current-project   (:current-project db)
          dataset-id  (if (= path '[:dataset-id]) (:dataset-id current-project) nil)
          id                (:id current-project)]
      (if (nil? dataset-id)
        {}
        {:api        (assoc (api/update-project id current-project)
                           :on-success [:projects2/get-project-data id])}))))


;;------------------------------------------------------------------------------
;; Debounce-updating project

(defn- new-list
  [list current-project id key data]
  (let [projects-update (remove (fn [project] (= (:id project) id)) list)
        updated-project (assoc-in current-project key data)]
    (cons updated-project projects-update)))


(rf/reg-event-fx
 :projects2/save-key
 in-projects2
 (fn [{:keys [db]} [_ path data]]
   (let [{:keys [list current-project]} db
         {:keys [id name]}              current-project
         path                           (if (vector? path) path [path])
         current-project-path           (into [:current-project] path)
         new-list                       (if (= path [:name])
                                          (new-list list {:id id :name name} id path data)
                                          list)]
     {:db                (-> db
                             (assoc-in current-project-path data)
                             (assoc :list new-list))
      :dispatch-debounce [{:id (str :projects2/save id)
                           :timeout 250
                           :action :dispatch
                           :event [:projects2/persist-current-project path]}]})))


(rf/reg-event-fx
  :projects2/persist-current-project
  in-projects2
  (fn [{:keys [db]} [_ path]]
    (let [current-project   (:current-project db)
          id                (:id current-project)]
      {:api         (assoc (api/update-project id current-project)
                           :on-success [:projects2/current-save path])})))

;;------------------------------------------------------------------------------
;; Listing projects

(rf/reg-event-fx
 :projects2/projects-list
 in-projects2
 (fn [{:keys [db]} _]
   {:api (assoc (api/list-projects)
                :on-success [:projects2/projects-listed])}))

(rf/reg-event-db
 :projects2/projects-listed
 in-projects2
 (fn [db [_ projects-list]]
   (assoc db :list projects-list)))
