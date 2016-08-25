(ns planwise.client.handlers
  (:require [planwise.client.db :as db]
            [planwise.client.projects.handlers :as projects]
            [planwise.client.datasets.handlers]
            [planwise.client.regions.handlers :as regions]
            [re-frame.utils :as c]
            [re-frame.core :refer [dispatch register-handler]]))

;; Event handlers
;; -----------------------------------------------------------------------

(register-handler
 :initialise-db
 (fn [_ _]
   (dispatch [:regions/load-regions])
   db/initial-db))

(defmulti on-navigate (fn [db page params] page))

(defmethod on-navigate :projects [db page {id :id, section :section, :as page-params}]
  (let [id (js/parseInt id)]
    (dispatch [:projects/navigate-project id section])
    db))

(defmethod on-navigate :home [db _ _]
  (dispatch [:projects/load-projects])
  db)

(defmethod on-navigate :datasets [db _ _]
  (dispatch [:datasets/load-datasets])
  db)

(defmethod on-navigate :default [db _ _]
  db)

(register-handler
 :navigate
 (fn [db [_ {page :page, :as params}]]
   (let [new-db (assoc db
                 :current-page page
                 :page-params params)]
     (on-navigate new-db page params))))

(register-handler
 :message-posted
 (fn [db [_ message]]
   (cond
     (= message "authenticated")
     (dispatch [:datasets/load-resourcemap-info])

     (#{"react-devtools-content-script"
        "react-devtools-bridge"}
      (aget message "source"))
     nil   ; ignore React dev tools messages

     true
     (do
       (println message)
       (c/warn "Invalid message received " message)))
   db))

(register-handler
 :tick
 (fn [db [_ time]]
   ;; FIXME
   #_(when (= 0 (mod time 3000))
     (dispatch [:datasets/update-import-status]))
   db))
