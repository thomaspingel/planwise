(ns planwise.client.sources.subs
  (:require [re-frame.core :as rf]
            [planwise.client.asdf :as asdf]
            [clojure.string :as str]))

(rf/reg-sub
 :sources/list-as-asdf
 (fn [db _]
   (let [sources (get-in db [:sources :list])]
     (when (asdf/should-reload? sources)
       (rf/dispatch [:sources/load]))
     sources)))

(rf/reg-sub
 :sources/list
 (fn [_]
   (rf/subscribe [:sources/list-as-asdf]))
 (fn [sources]
   (asdf/value sources)))

(rf/reg-sub
 :sources.new/data
 (fn [db _]
   (get-in db [:sources :new])))

(rf/reg-sub
 :sources.new/valid?
 (fn [_]
   (rf/subscribe [:sources.new/data]))
 (fn [new-source]
   (let [name (:name new-source)
         csv-file (:csv-file new-source)]
     (not (or (str/blank? name)
              (nil? csv-file))))))

(rf/reg-sub
 :sources.new/current-error
 (fn [db _]
   (get-in db [:sources :new :current-error])))
