(ns planwise.client.datasets2.components.dropdown
  (:require [re-frame.core :refer [subscribe dispatch]]
            [reagent.core :as r]
            [clojure.string :as string]
            [planwise.client.asdf :as asdf]
            [planwise.client.utils :as utils]
            [planwise.client.components.common2 :as common2]
            [planwise.client.datasets2.db :as db]
            [planwise.client.ui.rmwc :as m]
            [re-frame.utils :as c]))

(defn datasets-dropdown-component
  [{:keys [label value on-change]}]
  (let [datasets-sub     (subscribe [:datasets2/list])
        datasets-options (subscribe [:datasets2/dropdown-options])]
    (when (asdf/should-reload? @datasets-sub)
      (dispatch [:datasets2/load-datasets2]))
    [m/Select {:label (if (empty? @datasets-options) "There are no datasets defined." label)
               :disabled (empty? @datasets-options)
               :value (str value)
               :options (sort-by :label @datasets-options)
               :on-change #(on-change (js/parseInt (-> % .-target .-value)))}]))

(defn datasets-disabled-input-component
  [{:keys [label value]}]
  (let [datasets-sub     (subscribe [:datasets2/list])
        datasets-options (subscribe [:datasets2/dropdown-options])
        filtered         (filter (fn [el] (= (:value el) (str value))) @datasets-options)]
    (when (asdf/should-reload? @datasets-sub)
      (dispatch [:datasets2/load-datasets2]))
    [m/TextField {:type     "text"
                  :label    label
                  :value    (if (empty? filtered) "There are no datasets defined." (:label (first filtered)))
                  :disabled true}]))
