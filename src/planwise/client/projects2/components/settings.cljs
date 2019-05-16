(ns planwise.client.projects2.components.settings
  (:require [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch] :as rf]
            [re-com.core :as rc]
            [clojure.string :refer [blank? join]]
            [planwise.client.asdf :as asdf]
            [planwise.client.dialog :refer [dialog]]
            [planwise.client.components.common2 :as common2]
            [planwise.client.projects2.components.common :refer [delete-project-dialog]]
            [planwise.client.coverage :refer [coverage-algorithm-filter-options]]
            [planwise.client.providers-set.components.dropdown :refer [providers-set-dropdown-component]]
            [planwise.client.sources.components.dropdown :refer [sources-dropdown-component]]
            [planwise.client.mapping :refer [static-image fullmap-region-geo]]
            [planwise.client.routes :as routes]
            [planwise.client.ui.common :as ui]
            [planwise.client.ui.filter-select :as filter-select]
            [planwise.client.ui.rmwc :as m]
            [planwise.client.utils :as utils]
            [clojure.spec.alpha :as s]
            [planwise.model.project-consumers]
            [planwise.model.project-actions]
            [planwise.model.project-coverage]
            [planwise.model.project-providers]
            [planwise.model.project-review]
            [planwise.model.project-goal]))

;;------------------------------------------------------------------------
;;Current Project updating

(defn- regions-dropdown-component
  [attrs]
  (let [props (merge {:choices   @(rf/subscribe [:regions/list])
                      :label-fn  :name
                      :render-fn (fn [region] [:div
                                               [:span (:name region)]
                                               [:span.option-context (:country-name region)]])}
                     attrs)]
    (into [filter-select/single-dropdown] (mapcat identity props))))

(defn- current-project-input
  ([label path type]
   (current-project-input label path type "" "" {:disabled false}))
  ([label path type other-props]
   (current-project-input label path type "" "" {:disabled false}))
  ([label path type prefix suffix other-props]
   (let [current-project (rf/subscribe [:projects2/current-project])
         value           (or (get-in @current-project path) "")
         change-fn       #(rf/dispatch-sync [:projects2/save-key path %])
         props (merge (select-keys other-props [:class :disabled :sub-type])
                      {:prefix    prefix
                       :suffix    suffix
                       :label     label
                       :on-change (comp change-fn (fn [e] (-> e .-target .-value)))
                       :value     value})]
     (case type
       "number" [common2/numeric-field (assoc props :on-change change-fn)]
       [common2/text-field props]))))

(defn- project-start-button
  [_ project]
  [m/Button {:id         "start-project"
             :type       "button"
             :unelevated "unelevated"
             :disabled   (not (s/valid? :planwise.model.project/starting project))
             :on-click   (utils/prevent-default #(dispatch [:projects2/start-project (:id project)]))}
   (if (= (keyword (:state project)) :started) "Started ..." "Start")])


(defn- project-next-step-button
  [project step]
  [m/Button {:id         "start-project"
             :type       "button"
             :unelevated "unelevated"
             :disabled   (if (= step "review") (not (s/valid? :planwise.model.project/starting project)) false)
             :on-click   (utils/prevent-default #(if (= step "review")
                                                   (dispatch [:projects2/start-project (:id project)])
                                                   (dispatch [:projects2/next-step-project (:id project) step])))}
   "Continue"])

(defn- project-delete-button
  [state]
  [m/Button {:type     "button"
             :theme    ["text-secondary-on-secondary-light"]
             :on-click #(reset! state true)} "Delete"])

(defn- project-back-button
  []
  ; TODO - add on-click function and don't show it in first step
  [m/Button {:type     "button"
             :theme    ["text-secondary-on-secondary-light"]}
   "Back"])

(defn- tag-chip
  [props index input read-only]
  [m/Chip props [m/ChipText input]
   (when-not read-only [m/ChipIcon {:use "close"
                                    :on-click #(dispatch [:projects2/delete-tag index])}])])

(defn- tag-set
  [tags read-only]
  [m/ChipSet {:class "tags"}
   (for [[index tag] (map-indexed vector tags)]
     [tag-chip {:key (str "tag-" index)} index tag read-only])])

(defn tag-input []
  (let [value (r/atom "")]
    (fn []
      [common2/text-field {:type "text"
                           :placeholder "Type tag for filtering providers"
                           :on-key-press (fn [e] (when (and (= (.-charCode e) 13) (not (blank? @value)))
                                                   (dispatch [:projects2/save-tag @value])
                                                   (reset! value "")))
                           :on-change #(reset! value (-> % .-target .-value))
                           :value @value}])))

(defn- count-providers
  [tags {:keys [provider-set-id providers region-id]}]
  (let [{:keys [total filtered]} providers]
    (cond (nil? region-id) [:p "Select region first."]
          (nil? provider-set-id) [:p "Select provider set first."]
          :else [:p "Selected providers: " filtered " / " total])))

(defn- section-header
  [number title]
  [:div {:class-name "step-header"}
   [:h2 [:span title]]])

(defn- project-setting-title
  [icon title]
  [:div.project-setting-title [:p [m/Icon icon] title]])

;-------------------------------------------------------------------------------------------
; Actions
(defn- show-action
  [_ {:keys [idx action-name capacity investment] :as action} props]
  [:div {:class "project-setting"}
   [m/Button (merge
              {:type "button"
               :theme    ["text-secondary-on-secondary-light"]
               :on-click #(dispatch [:projects2/delete-action action-name idx])}
              props)
    [m/Icon "clear"]]
   (when (= action-name :build) "with a capacity of ")
   [current-project-input "" [:config :actions action-name idx :capacity] "number" "" "" (merge {:class "action-input"} props)]
   " would cost "
   [current-project-input "" [:config :actions action-name idx :investment] "number" "$" "" (merge {:class "action-input"} props)]])

(defn- listing-actions
  [{:keys [read-only? action-name list]}]
  [:div
   (for [[index action] (map-indexed vector list)]
     [show-action {:key (str action-name "-" index)} (assoc action :action-name action-name :idx index) {:disabled read-only?}])
   [m/Button  {:type "button"
               :disabled read-only?
               :theme    ["text-secondary-on-secondary-light"]
               :on-click #(dispatch [:projects2/create-action action-name])} [m/Icon "add"] "Add Option"]])

;-------------------------------------------------------------------------------------------

(defn- current-project-step-goal
  [read-only current-project]
  [:section.project-settings-section
   [section-header 1 "Goal"]
   [current-project-input "Goal" [:name] "text"]
   [m/TextFieldHelperText {:persistent true} "Enter the goal for this project"]

   [regions-dropdown-component {:label     "Region"
                                :on-change #(dispatch [:projects2/save-key :region-id %])
                                :model     (:region-id current-project)
                                :disabled? read-only}]])
(defn- current-project-step-consumers
  [read-only current-project]
  [:section {:class-name "project-settings-section"}
   [section-header 2 "Consumers"]
   [sources-dropdown-component {:label     "Consumer Dataset"
                                :value     (:source-set-id current-project)
                                :on-change #(dispatch [:projects2/save-key :source-set-id %])
                                :disabled?  read-only}]
   [current-project-input "Consumers Unit" [:config :demographics :unit-name] "text" {:disabled read-only}]
   [m/TextFieldHelperText {:persistent true} (str "How do you refer to the filtered population? (Eg: women)")]
   [:div.percentage-input
    [current-project-input "Target" [:config :demographics :target] "number" "" "%"  {:disabled read-only :sub-type :percentage}]
    [:p (str "of " (or (not-empty (get-in current-project [:config :demographics :unit-name])) "population") " should be considered")]]])

(defn- current-project-step-providers
  [read-only current-project tags]
  [:section {:class-name "project-settings-section"}
   [section-header 3 "Providers"]
   [providers-set-dropdown-component {:label     "Provider Dataset"
                                      :value     (:provider-set-id current-project)
                                      :on-change #(dispatch [:projects2/save-key :provider-set-id %])
                                      :disabled? read-only}]

   [current-project-input "Capacity Workload" [:config :providers :capacity] "number" {:disabled read-only :sub-type :float}]
   [m/TextFieldHelperText {:persistent true} (str "How many " (or (not-empty (get-in current-project [:config :demographics :unit-name])) "consumers") " can each provider handle?")]
   (when-not read-only [tag-input])
   [:label "Tags: " [tag-set tags read-only]]
   [count-providers tags current-project]])

(defn- current-project-step-coverage
  [read-only current-project]

  [:section {:class-name "project-settings-section"}
   [section-header 4 "Coverage"]
   [:div {:class "step-info"} "These values will be used to estimate the geographic coverage that your current sites are providing. That in turn will allow Planwise to calculate areas out of reach."]
   [coverage-algorithm-filter-options {:coverage-algorithm (:coverage-algorithm current-project)
                                       :value              (get-in current-project [:config :coverage :filter-options])
                                       :on-change          #(dispatch [:projects2/save-key [:config :coverage :filter-options] %])
                                       :empty              [:div {:class-name " no-provider-set-selected"} "First choose provider-set."]
                                       :disabled?          read-only}]])

(defn- current-project-step-actions
  [read-only current-project build-actions upgrade-actions]

  [:section {:class-name "project-settings-section"}
   [section-header 5 "Actions"]
   [:div {:class "step-info"} "Potential actions to increase access to services. Planwise will use these to explore and recommend the best alternatives."]
   [project-setting-title "account_balance" "Available budget"]
   [current-project-input "" [:config :actions :budget] "number" "$" "" {:disabled read-only :class "project-setting"}]
   [m/TextFieldHelperText {:persistent true} "Planwise will keep explored scenarios below this maximum budget"]

   [project-setting-title "domain" "Building a new provider..."]
   [listing-actions {:read-only?  read-only
                     :action-name :build
                     :list        build-actions}]

   [project-setting-title "arrow_upward" "Upgrading a provider so that it can satisfy demand would cost..."]
   [current-project-input "" [:config :actions :upgrade-budget] "number" "$" "" {:disabled read-only :class "project-setting"}]

   [project-setting-title "add" "Increase the capactiy of a provider by..."]
   [listing-actions {:read-only?   read-only
                     :action-name :upgrade
                     :list        upgrade-actions}]])

(defn- current-project-step-review
  [read-only current-project]
  [:section {:class "project-settings-section"}
   [section-header 6 "Review"]
   [:div {:class "step-info"} "After this step the system will search for different improvements scenarios based on the given parameters. Once started, the process will continue even if you leave the site. From the dashboard you will be able to see the scenarios found so far, pause the search and review the performed work."]
   [project-setting-title "location_on" "Kenya health facilities - ResMap 8902"]
   [project-setting-title "account_balance" "K 25,000,000"]
   [project-setting-title "people" "Kenya census 2005"]
   [project-setting-title "directions" "120 min walking distance, 40 min driving"]
   [project-setting-title "info" "A hospital with a capacity of 100 beds will provide service for 1000 pregnancies per year"]])

(defn current-project-settings-view
  [{:keys [read-only step sections]}]
  (let [current-project (subscribe [:projects2/current-project])
        build-actions   (subscribe [:projects2/build-actions])
        upgrade-actions (subscribe [:projects2/upgrade-actions])
        tags            (subscribe [:projects2/tags])]
    (fn [{:keys [read-only step]}]
      [m/Grid {:class-name "wizard"}
       [m/GridCell {:span 12 :class-name "steps"}
        (let [project @current-project]
          (map-indexed (fn [i iteration-step]
                         [:a {:key i
                              :class-name (join " " [(if (= (:step iteration-step) step) "active") (if (s/valid? (keyword (str "planwise.model.project-" (:step iteration-step)) "validation") project) "complete")])
                              :href (routes/projects2-show-with-step {:id (:id project) :step (:step iteration-step)})}
                          (if (s/valid? (:spec iteration-step) project) [m/Icon "done"] [:i (inc i)])
                          [:div (:title iteration-step)]]) sections))]
       [m/GridCell {:span 6}
        [:form.vertical
         (case step
           "goal" [current-project-step-goal read-only @current-project]
           "consumers" [current-project-step-consumers read-only @current-project]
           "providers" [current-project-step-providers read-only @current-project @tags]
           "coverage" [current-project-step-coverage read-only @current-project]
           "actions" [current-project-step-actions read-only @current-project @build-actions @upgrade-actions]

           "review" [current-project-step-review read-only @current-project]
           (dispatch [:projects2/infer-step @current-project]))]]
       [m/GridCell {:span 6}
        [:div.map]]])))

(defn edit-current-project
  []
  (let [page-params       (subscribe [:page-params])
        current-project (subscribe [:projects2/current-project])
        delete?         (r/atom false)
        hide-dialog     (fn [] (reset! delete? false))
        sections        [{:step "goal" :title "Goal" :spec :planwise.model.project-goal/validation}
                         {:step "consumers" :title "Consumers" :spec :planwise.model.project-consumers/validation}
                         {:step "providers" :title "Providers" :spec :planwise.model.project-providers/validation}
                         {:step "coverage" :title "Coverage" :spec :planwise.model.project-coverage/validation}
                         {:step "actions" :title "Actions" :spec :planwise.model.project-actions/validation}
                         {:step "review" :title "Review" :spec :planwise.model.project-review/validation}]]
    (fn []
      [ui/fixed-width (common2/nav-params)
       [ui/panel {}
        [current-project-settings-view {:read-only false :step (:step @page-params) :sections sections}]

        [:div {:class-name "project-settings-actions"}
         [project-back-button]
         [project-delete-button delete?]
         [project-next-step-button @current-project (:step @page-params)]]]
       [delete-project-dialog {:open? @delete?
                               :cancel-fn hide-dialog
                               :delete-fn #(dispatch [:projects2/delete-project (:id @current-project)])}]])))
