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

;Auxiliar functions

(defrecord demand-unit [lon lat value])
(defn location [demand-unit] [(:lon demand-unit) (:lat demand-unit)])
(defn value [demand-unit] (:value demand-unit))

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
  (let [set-points (map (fn [a] (conj (location a) (value a))) set-points)
        [r0 r1 total-w]  (reduce (fn [[r0 r1 partial-w] [l0 l1 w]]
                                   [(+ r0 (* w l0)) (+ r1 (* w l1)) (+ partial-w w)]) [0 0 0] set-points)]
    (if (pos? total-w)
      (map #(/ % total-w) [r0 r1])
      (first set-points))))

(defn get-centroid
  [points]
  (let [total (count points)
        [r0 r1] (reduce (fn [[r0 r1] [l0 l1]] [(+ r0 l0) (+ r1 l1)]) [0 0] points)]
    (if (pos? total) (map #(/ % total) [r0 r1]) (first points))))

(defn fast-raster-saturated-locations
  [raster cutoff]
  (let [{:keys [data nodata xsize geotransform]} raster
        saturated-indices (Algorithm/filterAndSortIndices data nodata cutoff)]
    (Algorithm/locateIndices data saturated-indices xsize geotransform)))

(defn get-saturated-locations
  [{:keys [raster sources-data]} [_ b0 b1 b2 _ :as demand-quartiles]]
 ; (info "raster" raster "and demand-quartiles " demand-quartiles)
  (if raster
    (fast-raster-saturated-locations raster b2)
    (mapv (fn [{:keys [lon lat quantity]}] [lon lat quantity]) (sort-by :quantity > sources-data))))

(defn mean-initial-data
  [n demand coverage-fn]
  (let [locations (take n (random-sample 0.8 demand))
        total-max (reduce (fn [tm location]
                            (let [{:keys [max]} (or (coverage-fn (drop-last location) {:get-avg true}) {:max 0})]
                              (+ tm max))) 0 locations)]
    (/ total-max n)))

(defn neighbour-fn*
  ([center bound]
   (fn [p]
     (< (euclidean-distance (location center) (location p)) bound)))
  ([center radius eps]
   (fn [p]
     (< (- (euclidean-distance (location center) (location p)) radius) eps))))

(defn get-demand-units
  [{:keys [raster sources-data]} [_ b0 b1 b2 _ :as demand-quartiles]]
  (mapv
   #(apply ->demand-unit %)
   (if raster
     (mapv vec (fast-raster-saturated-locations raster b2))
     (mapv (fn [{:keys [lon lat quantity]}] [lon lat quantity]) (sort-by :quantity > sources-data)))))

(defprotocol searchingSet
  (start-from [demand] "Location given to start maximal-neighbourhood search")
  (remove-first [demand] "Returns vector of demand-units"))

(defrecord demand-set* [demand]
  searchingSet
  (start-from [this] (first demand))
  (remove-first [this] (rest demand)))

(defn update-demand-set
  [demand-set new-vector]
  (assoc set :demand new-vector))

(defprotocol SearchContainer
  (source-type [source] "Demand source resources")
  (demand-set  [vector] [vector raster] "Vector of locations sorted by higher demand.")
  (selected-locations [selected-locations] "Locations computed after maximal-neighbourhood search.
                                            Its length is less or equal than iterations on searching algorithm")
  (coverage [coverage-fn] "Coverage criteria set to explore demand")
  (radius-coverage [bound] "Mean value of criteria coverage in demand sample"))

(defn condition-for-getting-locations
  [search-container times sample]
  (or (empty? (remove-first (demand-set search-container)))
      (nil? (start-from (demand-set search-container)))
      (= times sample)))

(defn update* [search-container key val]
  (if (= key :demand-set)
    (update-demand-set (demand-set search-container) val)
    (assoc search-container key val)))

(defn mean-initial-data*
  [n search-container]
  (let [demand      (take n
                          (random-sample 0.8
                                         (:demand (demand-set search-container))))
        coverage-fn (coverage search-container)
        total-max   (reduce (fn [tm demand-unit]
                              (let [{:keys [max]} (or (coverage-fn (location demand-unit) {:get-avg true}) {:max 0})]
                                (+ tm max))) 0 demand)]
    (/ total-max n)))

(defrecord RasterSearchContainer
           [search-path raster demand-quartiles selected-locations coverage-fn bound]
  SearchContainer
  (source-type [this] search-path)
  (demand-set  [this given-raster]
    (->demand-set* (get-demand-units {:raster given-raster} demand-quartiles)))
  (demand-set [this] (demand-set this raster))
  (selected-locations [this] selected-locations)
  (coverage [this] coverage-fn)
  (radius-coverage [this]
    (or bound
        (mean-initial-data* 30 this))))

; (defrecord PointSearchContainer
;            [sources-data demand-quartiles selected-locations coverage-fn bound]
;   SearchContainer
;   (source-type [this] sources-data)
;   (demand-set [this]
;     (-> demand-set* (get-demand-units {:sources-data sources-data} demand-quartiles)))
;   (selected-locations [this] selected-locations)
;   (coverage [this] coverage-fn)
;   (radius-coverage [this]
;     (or bound
;         (mean-initial-data* 30 this))))

; (def starting-point-search (-> PoitnSearchContainer sources-data initial-set max []))

(defn next-neighbour
  ([demand center radius]
   (next-neighbour demand center radius (/ radius 1000)))
  ([demand center radius eps]
   (let [in-frontier? (fn [p] (neighbour-fn* p radius eps))
         frontier     (filter (in-frontier? center) demand)
         [lon lat :as new]    (if-not (empty? frontier) (get-weighted-centroid frontier))]
     (if new
       (->demand-unit lon lat nil)
       center))))

(defn find-centroid-from-demand-unit
  [demand-unit search-container]
  (let [demand      (remove-first
                     (demand-set
                      search-container
                      (raster/read-raster (:search-path search-container))))
        avg-max     (radius-coverage search-container)
        is-neighbour? (memoize
                       (fn [center r]
                         (neighbour-fn* center r)))]
    (loop [sum      0
           radius   avg-max
           center    demand-unit
           interior (filter (is-neighbour? center avg-max) demand)]
      (if (<= (- avg-max sum) 0)
        center
        (let [next-center (next-neighbour demand center radius)
              next-radius (euclidean-distance (location center) (location next-center))
              step        (- radius next-radius)]
          (if (and (> step 0) (pos? next-radius))
            (recur (+ sum step) next-radius next-center (filter (is-neighbour? next-center next-radius)))
            (recur avg-max radius center interior)))))))


(defn update-visit
  [{:keys [xsize geotransform data] :as raster} visited]
  (let [idx ((fn [coord] (let [[x y] (coord->pixel geotransform coord)]
                           (+ (* y xsize) x))) visited)]
    (aset data idx (float 0))
    (assert (zero? (aget data idx)))
    (raster/create-raster-from-existing raster data)))

(defn update-searching-raster
  [search-path {:keys [lon lat]}]
  (let [raster (update-visit (raster/read-raster search-path) [lon lat])]
    (raster/write-raster raster search-path)))

(defn raster-update-demand-set
  [search-container]
  (let [searching-raster (raster/read-raster (:search-path search-container))
        center      (start-from (demand-set search-container searching-raster))
        coverage-fn (coverage search-container)
        raster      (:raster search-container)
        get-value-from-raster (fn [{:keys [lon lat] :as demand-unit}]
                                (aget (:data raster) (get-index [lon lat] raster)))
        final-center (let [geo-cent (find-centroid-from-demand-unit center search-container)]
                       (assoc geo-cent :value (get-value-from-raster geo-cent)))
        location     (:location-info (coverage-fn final-center {:get-update {}}))]
    (update-searching-raster (:search-path search-container) center)
    (info "Show Center " center)
    (if location
      (assoc search-container :selected-locations (conj (selected-locations search-container) location))
      search-container)))

(defn get-locations*
  [search-container sample]
  (loop [times 0
         container search-container]
    (info "TIMES " times)
    (if (condition-for-getting-locations container times sample)
      (remove #(zero? (:coverage %)) (selected-locations container))
      (let [locations              (selected-locations container)
            new-instance-container (raster-update-demand-set container)
            same-locations?        (= locations (selected-locations new-instance-container))]
        (if same-locations?
          (recur times new-instance-container)
            ;let demand* demand-set*. sort-by :value > remove-first demand-set
          (recur (inc times) new-instance-container))))))

(defn maximal-neighbourhood-search
  [sample search-container]
  (info "Starting search for ")
  (let [source    (source-type search-container)
        from      (start-from (demand-set search-container))
        locations (get-locations* search-container sample)]
    ;(when-let [search-path (:search-path search-container)]
     ;(clojure.java.io/delete-file search-path true))
    (sort-by :coverage > locations)))