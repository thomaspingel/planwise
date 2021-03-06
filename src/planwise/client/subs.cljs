(ns planwise.client.subs
  (:require [re-frame.core :as rf]
            [planwise.client.coverage]
            [planwise.client.projects2.subs]
            [planwise.client.sources.subs]
            [planwise.client.providers-set.subs]
            [planwise.client.regions.subs]
            [planwise.client.scenarios.subs]))


;; Subscriptions
;; -------------------------------------------------------

(rf/reg-sub
 :current-page
 (fn [db _]
   (:current-page db)))

(rf/reg-sub
 :page-params
 (fn [db _]
   (:page-params db)))
