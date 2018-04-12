(ns planwise.endpoint.projects2
  (:require [compojure.core :refer :all]
            [integrant.core :as ig]
            [taoensso.timbre :as timbre]
            [clojure.spec.alpha :as s]
            [ring.util.response :refer [response status not-found]]
            [planwise.util.ring :as util]
            [buddy.auth :refer [authenticated?]]
            [buddy.auth.accessrules :refer [restrict]]
            [planwise.model.projects2 :as model]
            [planwise.boundary.datasets2 :as datasets2]
            [planwise.boundary.projects2 :as projects2]))

(timbre/refer-timbre)

(defn- projects2-routes
  [{service :projects2}]
  (routes

   (POST "/" request
     (let [user-id    (util/request-user-id request)
           project-id (:id (projects2/create-project service user-id))
           project    (projects2/get-project service project-id)]
       (response project)))

   (PUT "/:id" [id project :as request]
     (let [user-id  (util/request-user-id request)
           id       (Integer. id)
           project  (assoc project :id id)] ;; honor id of route
       ;; TODO validate permission
       (assert (s/valid? ::model/project project) "Invalid project")
       (projects2/update-project service project)
       (response (projects2/get-project service id))))

   (GET "/:id" [id :as request]
     (let [user-id (util/request-user-id request)
           project (projects2/get-project service (Integer. id))]
       (if (nil? project)
         (not-found {:error "Project not found"})
         (response project))))

   (GET "/" request
     (let [user-id          (util/request-user-id request)
           list-of-projects (projects2/list-projects service user-id)]
       (response list-of-projects)))

   (POST "/:id/start" [id :as request]
     (let [user-id       (util/request-user-id request)
           id            (Integer. id)
           project       (projects2/get-project service id)]
       ;; TODO validate permission
       (if (nil? project)
         (not-found {:error "Project not found"})
         (do
           (projects2/start-project service id)
           (response (projects2/get-project service id))))))

   (POST "/:id/reset" [id :as request]
     (let [user-id       (util/request-user-id request)
           id            (Integer. id)
           project       (projects2/get-project service id)]
       ;; TODO validate permission
       (if (nil? project)
         (not-found {:error "Project not found"})
         (do
           (projects2/reset-project service id)
           (response (projects2/get-project service id))))))))


(defn projects2-endpoint
  [config]
  (context "/api/projects2" []
    (restrict (projects2-routes config) {:handler authenticated?})))

(defmethod ig/init-key :planwise.endpoint/projects2
  [_ config]
  (projects2-endpoint config))
