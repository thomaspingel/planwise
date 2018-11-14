(ns planwise.engine.suggestions
  (:require [planwise.boundary.providers-set :as providers-set]
            [planwise.boundary.sources :as sources-set]
            [planwise.component.coverage.greedy-search :as gs]
            [planwise.boundary.coverage :as coverage]
            [planwise.engine.raster :as raster]
            [planwise.engine.common :as common]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [planwise.component.coverage.rasterize :as rasterize]
            [planwise.engine.demand :as demand]
            [planwise.util.files :as files]
            [clojure.string :as string]
            [taoensso.timbre :as timbre]))

(timbre/refer-timbre)

;Auxiliar functions
(defn update-sources-data
  [engine {:keys [sources-data demand-quartiles]} polygon get-update]
  (coverage/locations-outside-polygon (:coverage engine) polygon (:demand get-update)))

(defn- get-resolution
  [raster]
  (let [[_ x-res _ _ _ y-res] (vec (:geotransform raster))]
    {:x-res x-res
     :y-res (- y-res)}))

(defn count-under-geometry
  [engine polygon {:keys [raster original-sources source-set-id geom-set]}]
  (if raster
    (let [coverage (raster/create-raster (rasterize/rasterize polygon (get-resolution raster)))]
      (demand/count-population-under-coverage raster coverage))
    (let [ids (set (sources-set/enum-sources-under-coverage (:sources-set engine) source-set-id polygon))]
      (reduce (fn [sum {:keys [quantity id]}] (+ sum (if (ids id) quantity 0))) 0 original-sources))))

(defn- update-search-path-data
  [engine search-path polygon]
  (let [searching-raster (raster/read-raster search-path)
        coverage-raster (raster/create-raster (rasterize/rasterize polygon (get-resolution searching-raster)))]
    (demand/multiply-population-under-coverage! searching-raster coverage-raster (float 0))
    (assert (zero? (count-under-geometry engine polygon {:raster searching-raster})))))

(defn- warp-raster
  [raster-path {:keys [x-res y-res]}]
  (let [raster-path* (string/split raster-path #"/")
        directory   (string/join "/" (drop-last raster-path*))
        file-name   (last raster-path*)
        new-file-name (str "new-resolution-" file-name)]
    (shell/sh "gdalwarp" "-tr" (str x-res) (str y-res) file-name new-file-name :dir (str directory))
    (str directory "/" new-file-name)))

(defn- get-paths-and-raster
  [raster project-id]
  (let [half-resolution-raster-path (warp-raster (str "data/" raster ".tif") {:x-res 0.017 :y-res -0.017})
        raster               (raster/read-raster half-resolution-raster-path)
        search-path          (files/create-temp-file (str "data/scenarios/" project-id "/coverage-cache/") "new-provider-" ".tif")]
    (raster/write-raster-file raster search-path)
    [half-resolution-raster-path search-path raster]))

;;TODO; refactor as multimethod
(defn get-coverage-for-suggestion
  [engine
   {:keys [criteria region-id project-capacity] :as project-info}
   {:keys [sources-data search-path] :as source}
   {:keys [provider-id coord get-avg get-update] :as props}]
  (let [updated-criteria      criteria
        [lon lat :as coord]   coord
        polygon               (if coord
                                (coverage/compute-coverage (:coverage engine) {:lat lat :lon lon} updated-criteria)
                                (:geom (providers-set/get-coverage
                                        (:providers-set engine)
                                        provider-id
                                        {:algorithm (name (:algorithm criteria))
                                         :region-id region-id
                                         :filter-options (dissoc criteria :algorithm)})))
        population-reacheable (count-under-geometry engine polygon source)
        coverage-info   {:coverage population-reacheable
                         :required-capacity (/ population-reacheable project-capacity)}
        extra-info-for-new-provider {:coverage-geom (:geom (coverage/geometry-intersected-with-project-region (:coverage engine) polygon region-id))
                                     :location {:lat lat :lon lon}}]
    (cond get-avg    {:max (coverage/get-max-distance-from-geometry (:coverage engine) polygon)}
          get-update (do (update-search-path-data engine search-path polygon)
                         {:location-info (merge coverage-info extra-info-for-new-provider)})
           ;:updated-demand (get-demand-source-updated engine source polygon get-update)}
          provider-id coverage-info
          :other     (merge coverage-info extra-info-for-new-provider))))

(defn raster-search-for-optimal-location
  [engine project raster]
  (let [{:keys [engine-config config provider-set-id coverage-algorithm]} project
        [new-raster-path search-path new-raster] (get-paths-and-raster raster (:id project))
        demand-quartiles (demand/compute-population-quartiles new-raster)
        source  {:raster new-raster
                 :initial-set (gs/get-saturated-locations {:raster new-raster} demand-quartiles)
                 :search-path search-path
                 :demand-quartiles demand-quartiles}
        criteria  (assoc (get-in project [:config :coverage :filter-options]) :algorithm (keyword coverage-algorithm))
        project-info {:criteria criteria
                      :region-id (:region-id project)
                      :project-capacity (get-in config [:providers :capacity])}
        coverage-fn (fn [demand-unit props]
                      (try
                        (get-coverage-for-suggestion engine project-info source (assoc props :coord (gs/location demand-unit)))
                        (catch Exception e
                          (warn (str "Failed to compute coverage for coordinates " (gs/location demand-unit)) e))))
        bound       (:avg-max (providers-set/get-radius-from-computed-coverage (:providers-set engine) criteria provider-set-id))
        starting-search-container (gs/->RasterSearchContainer search-path new-raster demand-quartiles [] coverage-fn bound)]
    (gs/maximal-neighbourhood-search 10 starting-search-container)))

;TODO; shared code with client
(defn- get-investment-from-project-config
  [capacity increasing-costs]
  (let [first     (first increasing-costs)
        last      (last increasing-costs)
        intervals (mapv vector increasing-costs (drop 1 increasing-costs))]
    (cond
      (<= capacity (:capacity first)) (:investment first)
      :else
      (let [[[a b] :as interval] (drop-while (fn [[_ b]] (and (not= last b) (< (:capacity b) capacity))) intervals)
            m     (/ (- (:investment b) (:investment a)) (- (:capacity b) (:capacity a)))]
        (+ (* m (- capacity (:capacity a))) (:investment a))))))

(defn- get-increasing-cost
  [{:keys [capacity action]} {:keys [upgrade-budget increasing-costs no-action-costs]}]
  (let [investment (when-not no-action-costs
                     (if (or (zero? capacity) (nil? capacity))
                       0
                       (get-investment-from-project-config capacity increasing-costs)))]
    (cond no-action-costs 1
          (= action "upgrade-provider") (+ investment (or upgrade-budget 0))
          :else investment)))

(defn get-provider-capacity-and-cost
  [provider settings]
  (let [max-capacity      (:max-capacity settings)
        action-capacity   (if max-capacity
                            (min (:required-capacity provider) (:max-capacity settings))
                            (:required-capacity provider))
        action-cost       (get-increasing-cost
                           {:action (if (:applicable? provider)
                                      "increase-provider"
                                      "upgrade-provider")
                            :capacity action-capacity}
                           settings)]
    (cond
      (< (:available-budget settings) action-cost) nil
      :else {:action-capacity action-capacity
             :action-cost     action-cost})))

(defn insert-in-sorted-coll
  [coll value criteria]
  (sort-by criteria > (conj coll value)))

(defn get-information-from-demand
  [all-providers id]
  (select-keys
   (first (filter #(= id (:id %)) all-providers))
   [:required-capacity]))

(defn get-sorted-providers-interventions
  [engine project {:keys [providers-data changeset] :as scenario} settings]
  (let [{:keys [engine-config config provider-set-id region-id coverage-algorithm]} project
        providers-collection (common/providers-in-project (:providers-set engine) project)]
    (reduce
     (fn [suggestions provider]
       (insert-in-sorted-coll
        suggestions
        (when-let [intervention (get-provider-capacity-and-cost
                                 (merge provider
                                        (get-information-from-demand providers-data (:id provider)))
                                 settings)]
          (merge
           provider
           intervention
           (let [{:keys [action-capacity action-cost]} intervention]
             {:ratio (if (not (zero? action-cost))
                       (/ action-capacity action-cost)
                       0)})))
        :ratio))
     []
     providers-collection)))

(comment
  ;REPL testing
  ;Correctnes of coverage

  (def raster (raster/read-raster "data/scenarios/44/initial-5903759294895159612.tif"))
  (def criteria {:algorithm :simple-buffer :distance 20})
  (def val 1072404)
  (def f (fn [val] (engine/get-coverage (:coverage engine) {:idx val} raster criteria)))
  (f val) ;idx:  1072404 | total:  17580.679855613736  |demand:  17580
          ;where total: (total-sum (vec (demand/get-coverage raster coverage)) data)
)

(comment
    ;REPL testing
    ;Timing
        ;Assuming computed providers

  (def projects2 (:planwise.component/projects2 integrant.repl.state/system))
  (def scenarios (:planwise.component/scenarios integrant.repl.state/system))
  (def providers-set (:planwise.component/providers-set integrant.repl.state/system))
  (def coverage (:planwise.component/coverage integrant.repl.state/system))
  (defn new-engine []
    (map->Engine {:providers-set providers-set :coverage coverage}))

  (new-engine)
  (require '[planwise.boundary.scenarios :as scenarios])

    ;Criteria: walking friction
  (def project   (projects2/get-project projects2 51))
  (def scenario (scenarios/get-scenario scenarios 362))
  (time (search-optimal-location (new-engine) project scenario)); "Elapsed time: 30125.428086 msecs"

    ;Criteria: driving friction
  (def project   (projects2/get-project projects2 57))
  (def scenario (scenarios/get-scenario scenarios 399))
  (time (search-optimal-location (new-engine) project scenario));"Elapsed time: 28535.980406 msecs"

    ;Criteria: pg-routing
  (def project   (projects2/get-project projects2 53))
  (def scenario  (scenarios/get-scenario scenarios 380))
  (time (search-optimal-location (new-engine) project scenario)); "Elapsed time: 16058.839293 msecs"

  ;Criteria: simple buffer
  (def project   (projects2/get-project projects2 55))
  (def scenario  (scenarios/get-scenario scenarios 382))
  (time (search-optimal-location (new-engine) project scenario)));"Elapsed time: 36028.555081 msecs"

;Testing over Kilifi
  ;;Efficiency
    ;;Images

(comment
  (defn generate-raster-sample
    [coverage locations criteria]
    (let [kilifi-pop (raster/read-raster "data/kilifi.tif")
          new-pop    (raster/read-raster "data/cerozing.tif")
          dataset-fn (fn [loc] (let [polygon (coverage/compute-coverage coverage loc criteria)] (rasterize/rasterize polygon)))
          get-index  (fn [dataset] (vec (demand/get-coverage kilifi-pop (raster/create-raster dataset))))
          same-values (fn [set] (map (fn [i] [i (aget (:data kilifi-pop) i)]) set))
          set         (reduce into (mapv #(-> % dataset-fn get-index same-values) locations))
          new-data    (reduce (fn [new-data [idx val]] (assoc new-data idx val)) (vec (:data new-pop)) set)
          raster      (raster/create-raster-from-existing kilifi-pop (float-array new-data))]
      raster))

  (defn generate-project
    [raster {:keys [algorithm] :as criteria}]
    (let [demand-quartiles (vec (demand/compute-population-quartiles raster))
          provider-set-id {:walking-friction 5 :pgrouting-alpha 7
                           :driving-friction 8 :simple-buffer 4}]
      {:bbox '[(-3.9910888671875 40.2415275573733) (-2.3092041015625 39.0872802734376)]
       :region-id 85, :config {:coverage {:filter-options (dissoc criteria :algorithm)}}
       :provider-set-id (algorithm provider-set-id) :source-set-id 2 :owner-id 1
       :engine-config {:demand-quartiles demand-quartiles
                       :source-demand 1311728}
       :coverage-algorithm (name algorithm)}))


;;For visualizing effectiveness
  (def criteria {:algorithm :walking-friction, :walking-time 120})
  (def criteria {:algorithm :pgrouting-alpha :driving-time 60})
  (def criteria {:algorithm :driving-friction :driving-time 90})

 ;Test 0
  (def locations0 [{:lon 39.672257821715334, :lat -3.8315073359981278}])
  (def raster-test0 (generate-raster-sample coverage locations criteria))

  ;Test 1
  (def locations1 [{:lon 39.863 :lat -3.097} {:lon 39.672257821715334, :lat -3.8315073359981278}])
  (def raster-test1 (generate-raster-sample coverage locations1 criteria))

  ;Test 2
  (def locations2 [{:lon 39.672257821715334, :lat -3.8315073359981278} {:lon 39.863 :lat -3.097} {:lon 39.602 :lat -3.830}])
  (def raster-test2 (generate-raster-sample coverage locations2 criteria))

  ;Test 3
  (def locations3 [{:lon 39.672257821715334, :lat -3.8315073359981278} {:lon 39.863 :lat -3.097} {:lon 39.479 :lat -3.407}])
  (def raster-test3 (generate-raster-sample coverage locations3 criteria))

  (def project-test (generate-project raster-test criteria))
  (search-optimal-location engine project-test {} raster-test))