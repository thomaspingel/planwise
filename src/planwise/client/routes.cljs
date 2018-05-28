(ns planwise.client.routes
  (:require-macros [secretary.core :refer [defroute]])
  (:require [re-frame.core :refer [dispatch]]
            [secretary.core]))

;; -------------------------
;; Routes

(defroute home "/" []
  (dispatch [:navigate {:page :home}]))
(defroute home-old "/old" []
  (dispatch [:navigate {:page :home-old}]))
(defroute project-demographics "/projects/:id" [id]
  (dispatch [:navigate {:page :projects, :id id, :section :demographics}]))
(defroute project-facilities "/projects/:id/facilities" [id]
  (dispatch [:navigate {:page :projects, :id id, :section :facilities}]))
(defroute project-transport "/projects/:id/transport" [id]
  (dispatch [:navigate {:page :projects, :id id, :section :transport}]))
(defroute project-scenarios "/projects/:id/scenarios" [id]
  (dispatch [:navigate {:page :projects, :id id, :section :scenarios}]))
(defroute project-access "/projects/:id/access/:token" [id token]
  (dispatch [:navigate {:page :projects, :id id, :section :access, :token token}]))
(defroute providers-set "/providers-set" []
  (dispatch [:navigate {:page :providers-set}]))
(defroute datasets "/datasets" []
  (dispatch [:navigate {:page :datasets}]))
(defroute projects2 "/projects2" []
  (dispatch [:navigate {:page :projects2, :section :index}]))
(defroute projects2-show "/projects2/:id" [id]
  (dispatch [:navigate {:page :projects2, :id (js/parseInt id), :section :show}]))
(defroute design "/_design" []
  (dispatch [:navigate {:page :design}]))
(defroute design-section "/_design/:section" [section query-params]
  (dispatch [:navigate {:page :design, :section (keyword section), :query-params query-params}]))
(defroute download-sample "/sample.csv" [])
(defroute scenarios "/projects2/:project-id/scenarios/:id" [project-id id]
  (dispatch [:navigate {:page :scenarios, :id (js/parseInt id), :project-id (js/parseInt project-id)}]))

(defroute projects2-scenarios "/projects2/:id/scenarios" [id]
  (dispatch [:navigate {:page :projects2, :id (js/parseInt id), :section :project-scenarios}]))
(defroute projects2-settings "/projects2/:id/settings" [id]
  (dispatch [:navigate {:page :projects2, :id (js/parseInt id), :section :project-settings}]))
