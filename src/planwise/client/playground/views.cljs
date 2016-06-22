(ns planwise.client.playground.views
  (:require [leaflet.core :refer [map-widget]]
            [planwise.client.mapping :refer [default-base-tile-layer]]
            [planwise.client.slider :refer [threshold-slider]]
            [planwise.client.hud :refer [coords-and-info]]
            [re-frame.core :refer [subscribe dispatch]]))

(defn playground-page []
  (let [playground (subscribe [:playground])]
    (fn []
      (let [view (:map-view @playground)
            points (:points @playground)
            isochrone (:isochrone @playground)
            facilities (:facilities @playground)
            threshold (:threshold @playground)
            node-id (:node-id @playground)
            geojson (:geojson @playground)
            position (:position view)
            zoom (:zoom view)]
        [:div
         [threshold-slider {:value threshold
                            :on-change #(dispatch [:playground/update-threshold %])}]
         [map-widget {:height 650
                      :position position
                      :zoom zoom
                      :on-click
                      #(dispatch [:playground/map-clicked %1 %2 %3])
                      :on-position-changed
                      #(dispatch [:playground/update-position %])
                      :on-zoom-changed
                      #(dispatch [:playground/update-zoom %])}
          default-base-tile-layer
          [:point-layer {:points (map (fn [fac] [(fac "lat") (fac "lon")]) facilities)
                         :radius 3
                         :color "#f00"
                         :opacity 0.3
                         :fillOpacity 0.3}]
          [:geojson-layer {:data geojson
                           :color "#f00"
                           :opacity 0.2
                           :weight 2}]
          [:geojson-layer {:data isochrone
                           :weight 3
                           :color "#00f"}]
          [:marker-layer {:points points}]]
         [coords-and-info {:lat (first position)
                           :lon (second position)
                           :zoom zoom
                           :node-id node-id
                           :on-reset-view
                           #(dispatch [:playground/reset-view])
                           :on-load-geojson
                           #(dispatch [:playground/load-geojson])}]]))))