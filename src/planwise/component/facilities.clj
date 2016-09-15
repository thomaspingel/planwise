(ns planwise.component.facilities
  (:require [com.stuartsierra.component :as component]
            [planwise.component.runner :refer [run-external]]
            [planwise.util.str :refer [trim-to-int]]
            [clojure.java.jdbc :as jdbc]
            [hugsql.core :as hugsql]
            [taoensso.timbre :as timbre]
            [clojure.string :refer [trim trim-newline join lower-case split-lines blank?]]))

(timbre/refer-timbre)

;; ----------------------------------------------------------------------
;; Auxiliary and utility functions

(hugsql/def-db-fns "planwise/sql/facilities.sql")

(defn get-db
  "Retrieve the database connection for a service"
  [component]
  (get-in component [:db :spec]))

(defn- isochrone-params [{:keys [threshold algorithm simplify]}]
  {:threshold (or threshold 900)
   :algorithm (or algorithm "alpha-shape")
   :simplify  (or simplify 0.001)})


;; ----------------------------------------------------------------------
;; Service definition

(defrecord FacilitiesService [config db runner])

(defn facilities-service
  "Construct a Facilities Service component"
  [config]
  (map->FacilitiesService {:config config}))


;; ----------------------------------------------------------------------
;; Service functions

(defn insert-facilities! [service dataset-id facilities]
  (jdbc/with-db-transaction [tx (get-db service)]
    (reduce (fn [ids facility]
              (let [result (insert-facility! tx (assoc facility :dataset-id dataset-id))]
                (conj ids (:id result))))
            []
            facilities)))

(defn destroy-facilities! [service dataset-id]
  (delete-facilities-in-dataset! (get-db service) {:dataset-id dataset-id}))

(defn list-facilities
  ([service dataset-id]
   (select-facilities-in-dataset (get-db service) {:dataset-id dataset-id}))
  ([service dataset-id criteria]
   (facilities-in-dataset-by-criteria
     (get-db service)
     {:dataset-id dataset-id
      :criteria (criteria-snip criteria)})))

(defn count-facilities
  ([service dataset-id]
   (count-facilities service dataset-id {}))
  ([service dataset-id criteria]
   (let [db (get-db service)
         criteria (criteria-snip criteria)
         result (count-facilities-in-dataset-by-criteria db {:dataset-id dataset-id
                                                             :criteria criteria})]
     (:count result))))

(defn isochrones-in-bbox
  ([service dataset-id isochrone-opts criteria]
   (isochrones-for-dataset-in-bbox* (get-db service)
     (-> (isochrone-params isochrone-opts)
         (merge (select-keys criteria [:bbox :excluding]))
         (assoc :dataset-id dataset-id
                :criteria (criteria-snip criteria))))))

(defn polygons-in-region
  [service dataset-id isochrone-options criteria]
  (select-polygons-in-region (get-db service) (assoc (isochrone-params isochrone-options)
                                               :dataset-id dataset-id
                                               :region-id (:region criteria)
                                               :criteria (criteria-snip criteria))))

(defn list-types [service dataset-id]
  (select-types-in-dataset (get-db service) {:dataset-id dataset-id}))

(defn destroy-types!
  [service dataset-id]
  (delete-types-in-dataset! (get-db service) {:dataset-id dataset-id}))

(defn insert-types!
  [service dataset-id types]
  (jdbc/with-db-transaction [tx (get-db service)]
    (-> (map (fn [type]
               (let [type-id (insert-type! tx (assoc type :dataset-id dataset-id))]
                 (merge type type-id)))
             types)
        (vec))))

(defn raster-isochrones! [service facility-id]
  (let [facilities-polygons-regions (select-facilities-polygons-regions-for-facility (get-db service) {:facility-id facility-id})
        facilities-polygons-by-region (group-by :region-id facilities-polygons-regions)]

    (doseq [[region-id facilities-polygons] facilities-polygons-by-region]
      (try
        (do
          ; First generate the raster masks for each isochrone in the region
          (doseq [{facility-polygon-id :facility-polygon-id} facilities-polygons]
            (run-external (:runner service) :scripts 60000 "raster-isochrone" (str region-id) (str facility-polygon-id)))
          ; Then count the population on each raster using the region raster as a basis, and update the DB
          (let [response (apply run-external
                           (:runner service) :bin 60000
                           "aggregate-isochrones-population"
                           ; TODO: Do not hardcode data paths
                           (str "data/populations/data/" region-id ".tif")
                           (mapv #(str "data/isochrones/" region-id "/" (:facility-polygon-id %) ".tif") facilities-polygons))
                populations (->> response
                              (split-lines)
                              (filter (complement blank?))
                              (map trim-to-int))
                facilities-polygons-with-population (map #(assoc %1 :population %2) facilities-polygons populations)]
            (doseq [fpr facilities-polygons-with-population]
              (set-facility-polygon-region-population! (get-db service) fpr))))
        (catch Exception e
          (error e "Error on raster-isochrone for facility" facility-id "region" region-id)
          nil)))))

(defn calculate-isochrones-population! [service facility-id country]
  (let [facilities-polygons (select-facilities-polygons-for-facility (get-db service) {:facility-id facility-id})]
    (doseq [{facility-polygon-id :facility-polygon-id, :as fp} facilities-polygons]
      (try
        (let [population (-> (run-external (:runner service) :scripts 60000 "isochrone-population" (str facility-polygon-id) country)
                             (trim-to-int))]
          (set-facility-polygon-population!
            (get-db service)
            (assoc fp :population population)))
        (catch Exception e
          (error e "Error on calculate-isochrones-population for facility" facility-id "polygon" facility-polygon-id)
          nil)))))

(defn preprocess-isochrones
 ([service]
  (let [ids (map :id (select-unprocessed-facilities-ids (get-db service)))]
    (doall (mapv (partial preprocess-isochrones service) ids))))
 ([service facility-id]
  (let [[{code :code country :country}] (calculate-facility-isochrones! (get-db service)
                                                           {:id facility-id
                                                            :method "alpha-shape"
                                                            :start 30
                                                            :end 180
                                                            :step 15})
        success? (= "ok" code)
        raster-isochrones? (get-in service [:config :raster-isochrones])]

    (debug (str "Facility " facility-id " isochrone processed with result: " (vec code)))
    (when (and success? raster-isochrones?)
      (calculate-isochrones-population! service facility-id country)
      (raster-isochrones! service facility-id))

    (keyword code))))

(defn clear-facilities-processed-status!
  [service]
  (clear-facilities-processed-status* (get-db service)))
