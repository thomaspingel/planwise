(ns planwise.component.coverage.greedy-search
  (:require [planwise.engine.raster :as raster]
            [planwise.util.files :as files]
            [clojure.core.memoize :as memoize]
            [planwise.component.coverage :as coverage]
            [taoensso.timbre :as timbre])
  (:import [java.lang.Math]
           [planwise.engine Algorithm]
           [org.gdal.gdalconst gdalconst]))

(timbre/refer-timbre)

;; Idea of algorithm:
 ;; i.   Fixed radius according to coverage algorithm:
          ;From computed providers
          ;or from random sampling of last quartile of demand.
 ;; ii.  Sort demand points by weight to set priorities.
 ;; iii. Given a high demand point, look for good enough neighbourhood (of radius obtained in (i)) in which is contained.
 ;; iv.  Once grouped, calculate centroide of group and apply coverage function."
;---------------------------------------------------------------------------------------------------------
(defn fast-raster-saturated-locations
  [raster cutoff]
  (let [{:keys [data nodata xsize geotransform]} raster
        saturated-indices (Algorithm/filterAndSortIndices data nodata cutoff)]
    (Algorithm/locateIndices data saturated-indices xsize geotransform)))

(defn get-saturated-locations
  [{:keys [raster sources-data]} [_ b0 b1 b2 _ :as demand-quartiles]]
  (if raster
    (fast-raster-saturated-locations raster b2)
    (mapv (fn [{:keys [lon lat quantity]}] [lon lat quantity]) (sort-by :quantity > sources-data))))


(defrecord demand-unit [lon lat value])
(defn location [demand-unit] (select-keys demand-unit [:lon :lat]))
(defn value [demand-unit] (:value demand-unit))

(defn get-demand-units
  [{:keys [raster sources-data]} [_ b0 b1 b2 _ :as demand-quartiles]]
  (map
   #(apply ->demand-unit %)
   (if raster
     (mapv vec (fast-raster-saturated-locations raster b2))
     (mapv (fn [{:keys [lon lat quantity]}] [lon lat quantity]) (sort-by :quantity > sources-data)))))

(defprotocol searchingSet
  (start-from [demand] "Location given to start maximal-neighbourhood search")
  (remove-first [demand] "Returns vector of demand-units"))

(defrecord searching-set* [demand]
  searchingSet
  (start-from [this] (first demand))
  (remove-first [this] (rest demand)))

(defn update-search
  [s new-vector]
  (assoc s :demand new-vector))

(defprotocol SearchContainer
  (source-type [source] "Demand source resources")
  (searching-set [initial-locations] "Vector of locations sorted by higher demand.")
  (selected-locations [selected-locations] "Locations computed after maximal-neighbourhood search.
                                            Its length is less or equal than iterations on searching algorithm")
  (coverage [coverage-fn] "Coverage criteria set to explore demand")
  (radius-coverage [bound] "Mean value of criteria coverage in demand sample"))

(defn condition-for-getting-locations
  [search-container times sample]
  (or (empty? (remove-first (searching-set search-container)))
      (nil? (start-from (searching-set search-container)))
      (= times sample)))

(defn update* [search-container key val]
  (if (= key :searching-set)
    (update-search (searching-set search-container) val)
    (assoc search-container key val)))

(defn find-centroid-and-update-demand
  [search-container]
  (let [coverage-fn (coverage search-container)
        source      (source-type search-container) ;map
        from        (start-from (searching-set search-container))
        demand      (remove-first (searching-set search-container))
        avg-max     (radius-coverage search-container)]
  ;(update-demand coverage-fn source from demand bound)
))

(defn mean-initial-data*
  [n search-container]
  (let [demand      (take n
                          (random-sample 0.8
                                         (:demand (searching-set search-container))))
        coverage-fn (coverage search-container)
        total-max   (reduce (fn [tm demand-unit]
                              (let [{:keys [max]} (or (coverage-fn (location demand-unit) {:get-avg true}) {:max 0})]
                                (+ tm max))) 0 demand)]
    (/ total-max n)))

(defrecord RasterSearchContainer
           [search-path raster demand-quartiles selected-locations coverage-fn bound]
  SearchContainer
  (source-type [this] search-path)
  (searching-set [this]
    (->searching-set* (get-demand-units {:raster raster} demand-quartiles)))
  (selected-locations [this] selected-locations)
  (coverage [this] coverage-fn)
  (radius-coverage [this]
    (or bound
        (mean-initial-data* 30 this))))

(defrecord PointSearchContainer
           [sources-data demand-quartiles selected-locations coverage-fn bound]
  SearchContainer
  (source-type [this] sources-data)
  (searching-set [this]
    (-> searching-set* (get-demand-units {:sources-data sources-data} demand-quartiles)))
  (selected-locations [this] selected-locations)
  (coverage [this] coverage-fn)
  (radius-coverage [this]
    (or bound
        (mean-initial-data* 30 this))))

; (def starting-raster-search (-> RasterSearchContainer search-path initial-set max []))
; (def starting-point-search (-> PoitnSearchContainer sources-data initial-set max []))
; ;---------------------------------------------------------------------------------------------------------
;Auxiliar functions

(defn euclidean-distance-squared
  [[a0 a1] [b0 b1]]
  (+ (* (- b0 a0) (- b0 a0)) (* (- b1 a1) (- b1 a1))))

(defn euclidean-distance
  [a b]
  (Math/sqrt (euclidean-distance-squared a b)))

(defn pixel->coord
  [geotransform pixels-vec]
  (let [[x0 xres _ y0 _ yres] (vec geotransform)
        coord-fn (fn [[x y]] [(+ x0 (* x xres)) (+ y0 (* y yres))])]
    (coord-fn pixels-vec)))

(defn coord->pixel
  [geotransform coord-vec]
  (let [[x0 xres _ y0 _ yres] (vec geotransform)
        pix-fn (fn [[lon lat]] [(Math/round (/ (- lon x0) xres)) (Math/round (/ (- lat y0) yres))])]
    (pix-fn coord-vec)))

(defn get-pixel
  [idx xsize]
  (let [y (quot idx xsize)
        x (mod idx xsize)]
    [x y]))

(defn get-geo
  [idx {:keys [xsize ysize geotransform]}]
  (pixel->coord geotransform (get-pixel idx xsize)))

(defn get-index
  [[lon lat] {:keys [xsize geotransform]}]
  (let [[x y :as pixel-vec] (coord->pixel geotransform [lon lat])]
    (+ (* y xsize) x)))

(defn get-weighted-centroid
  [set-points]
  (let [[r0 r1 total-w]  (reduce (fn [[r0 r1 partial-w] [l0 l1 w]]
                                   [(+ r0 (* w l0)) (+ r1 (* w l1)) (+ partial-w w)]) [0 0 0] set-points)]
    (if (pos? total-w)
      (map #(/ % total-w) [r0 r1])
      (first set-points))))

(defn get-centroid
  [set-points]
  (let [total (count set-points)
        [r0 r1] (reduce (fn [[r0 r1] [l0 l1]] [(+ r0 l0) (+ r1 l1)]) [0 0] set-points)]
    (if (pos? total) (map #(/ % total) [r0 r1]) (first set-points))))

(defn mean-initial-data
  [n demand coverage-fn]
  (let [locations (take n (random-sample 0.8 demand))
        total-max (reduce (fn [tm location]
                            (let [{:keys [max]} (or (coverage-fn (drop-last location) {:get-avg true}) {:max 0})]
                              (+ tm max))) 0 locations)]
    (/ total-max n)))

(defn neighbour-fn
  ([coord bound]
   (println coord bound)
   (fn [[lon lat _]] (< (euclidean-distance (drop-last coord) [lon lat]) bound)))
  ([coord radius eps]
   (fn [[lon lat _]] (< (- (euclidean-distance (drop-last coord) [lon lat]) radius) eps))))

(defn next-neighbour
  ([demand center radius]
   (next-neighbour demand center radius (/ radius 1000)))
  ([demand center radius eps]
   (let [in-frontier? (fn [p] (neighbour-fn p radius eps))
         frontier     (filter (in-frontier? center) demand)]
     (get-weighted-centroid frontier))))

(defn update-demand
  [coverage-fn {:keys [sources-data raster]} demand-point demand avg-max]
  {:pre [(and (not (nil? demand)) (not (nil? demand-point)))]}

  (let [source (or raster sources-data)
        get-value-fn  (if raster
                        (fn [location] (aget (:data source) (get-index location source)))
                        (fn [location] (last (filter #(= (drop-last %) location) source))))
        is-neighbour? (memoize (fn [coord r] (neighbour-fn coord r)))
        interior      (filter (is-neighbour? demand-point avg-max) demand)]

    (if (empty? interior)

      (let [division (group-by (is-neighbour? demand-point avg-max) demand)
            {:keys [location-info updated-demand]} (coverage-fn (drop-last demand-point) {:get-update {:demand demand}})]
        [location-info updated-demand])

      (loop [sum 0
             radius avg-max
             [lon lat _ :as center] demand-point
             visited #{[lon lat]}
             interior interior]

        (println "demand" demand-point)

        (if (<= (- avg-max sum) 0)

          (let [interior (filter (is-neighbour? center avg-max) demand)
                geo-cent (get-centroid interior)
                {:keys [location-info updated-demand]} (coverage-fn geo-cent {:get-update {:demand demand
                                                                                           :visited visited}})]
            [location-info updated-demand])

          (let [location (next-neighbour interior center radius)
                visited  (clojure.set/union visited #{(vec location)})]

            (if (nil? location)

              (recur avg-max radius center visited demand)

              (let [value       (get-value-fn location)
                    next-center (conj (vec location) value)
                    next-radius (euclidean-distance [lon lat] location)
                    step        (- radius next-radius)]


                (if (and (> step 0) (pos? next-radius))
                  (recur (+ sum step) next-radius next-center visited (filter (is-neighbour? next-center next-radius) demand))
                  (recur avg-max radius center visited interior))))))))))

(defn get-locations
  [coverage-fn source from initial-set bound sample]
  {:pre [(pos? bound)]}

  (loop [times 0
         locations []
         from   (first initial-set)
         demand (rest initial-set)]

    (if (or (nil? demand) (nil? from) (= times sample))
      (remove #(zero? (:coverage %)) locations)
      (let [[location demand*] (update-demand coverage-fn source from demand bound)]
        (if location
          (let [[from* & demand* :as set] (when demand* (sort-by last > demand*))]
            (recur (inc times) (conj locations location) from* demand*))
          (recur (inc times) locations (first demand*) (rest demand*)))))))

(defn get-locations*
  [search-container sample]
  (loop [times 0
         container search-container]
    (if (condition-for-getting-locations container sample)
      (remove #(zero? (:coverage %)) (selected-locations container))
      (let [locations              (selected-locations container)
            new-instance-container (find-centroid-and-update-demand search-container)
            same-locations?        (= locations (selected-locations new-instance-container))]
        (if same-locations?
          (recur times new-instance-container)
            ;let demand* searching-set*. sort-by :value > remove-first searching-set
          (recur (inc times) new-instance-container))))))

(defn greedy-search
  [sample {:keys [search-path initial-set sources-data] :as source} coverage-fn demand-quartiles {:keys [n bound]}]
  (info "Starting greedy search for " (or (first sources-data) (str "raster with " (count initial-set) " demand")))
  (let [[max & remain :as initial-set] (or initial-set sources-data)
        bound        (or bound (mean-initial-data 30 initial-set coverage-fn))
        locations    (get-locations coverage-fn source max initial-set (/ bound 2) sample)]
    ;(when search-path (clojure.java.io/delete-file search-path true))
    (sort-by :coverage > locations)))

(defn maximal-neighbourhood-search
  [sample search-container]
  (info "Starting search for ")
  (let [source    (source-type search-container)
        from      (start-from (searching-set search-container))
        locations (get-locations* search-container sample)]
    ;(when-let [search-path (:search-path search-container)]
     ;(clojure.java.io/delete-file search-path true))
    (sort-by :coverage > locations)))