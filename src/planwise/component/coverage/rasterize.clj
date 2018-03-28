(ns planwise.component.coverage.rasterize
  (:require [clojure.spec.alpha :as s]
            [planwise.util.pg :as pg])
  (:import [org.gdal.gdal gdal]
           [org.gdal.ogr ogr Feature FeatureDefn Geometry]
           [org.gdal.gdalconst gdalconst]
           [org.gdal.osr osrConstants SpatialReference]
           [org.postgis PGgeometry]))

(gdal/AllRegister)
(ogr/RegisterAll)

(defn memory-datasource
  ([]
   (memory-datasource "memory-ds"))
  ([name]
   (let [driver (ogr/GetDriverByName "Memory")]
     (.CreateDataSource driver name))))

(defn srs-from-epsg
  [epsg]
  (doto (SpatialReference.)
    (.ImportFromEPSG epsg)))

(defn srs-from-pg
  [pg]
  (let [srid (.. pg (getGeometry) (getSrid))]
    (srs-from-epsg srid)))

(defn pg->geometry
  [pg]
  (let [wkt (second (PGgeometry/splitSRID (str pg)))]
    (Geometry/CreateFromWkt wkt)))

(defn build-datasource-from-geometry
  [srs geometry]
  (let [mem-ds (memory-datasource)
        layer (.CreateLayer mem-ds "geometry" srs)
        feature-defn (.GetLayerDefn layer)
        feature (doto (Feature. feature-defn)
                  (.SetGeometryDirectly geometry))]
    (.CreateFeature layer feature)
    mem-ds))

(defn build-mask-raster-file
  [name {:keys [srs width height geotransform]}]
  (let [block-size-x 128
        block-size-y 128
        driver (gdal/GetDriverByName "GTiff")
        options (into-array String ["NBITS=1"
                                    "COMPRESS=CCITTFAX4"
                                    (str "BLOCKXSIZE=" block-size-x)
                                    (str "BLOCKYSIZE=" block-size-y)])
        dataset (.Create driver name width height 1 gdalconst/GDT_Byte options)]
    (doto dataset
      (.SetProjection (.ExportToWkt srs))
      (.SetGeoTransform (double-array geotransform)))
    (.. dataset (GetRasterBand 1) (SetNoDataValue 0.0))
    dataset))

(defn envelope
  [geom]
  (let [env (double-array 4)]
    (.GetEnvelope geom env)
    (let [[min-lon max-lon min-lat max-lat] env]
      {:min-lon min-lon
       :min-lat min-lat
       :max-lon max-lon
       :max-lat max-lat})))

(defn compute-aligned-raster-extent
  [{:keys [min-lon min-lat max-lon max-lat] :as envelope}
   {ref-lat :lat ref-lon :lon}
   {:keys [x-res y-res]}]
  (let [off-lon    (- min-lon ref-lon)
        off-lat    (- max-lat ref-lat)
        off-width  (Math/floor (/ off-lon x-res))
        off-height (Math/ceil (/ off-lat y-res))
        ul-lon     (+ ref-lon (* x-res off-width))
        ul-lat     (+ ref-lat (* y-res off-height))
        env-width  (- max-lon ul-lon)
        env-height (- ul-lat min-lat)
        width      (inc (int (Math/ceil (/ env-width x-res))))
        height     (inc (int (Math/ceil (/ env-height y-res))))]
    {:width        width
     :height       height
     :geotransform [ul-lon x-res         0
                    ul-lat     0 (- y-res)]}))

(s/def ::ref-coords ::pg/coords)
(s/def ::pixel-resolution number?)
(s/def ::x-res ::pixel-resolution)
(s/def ::y-res ::pixel-resolution)
(s/def ::resolution (s/keys :req-un [::x-res ::y-res]))
(s/def ::rasterize-options (s/keys :req-un [::ref-coords ::resolution]))

(defn rasterize
  [polygon output-path {:keys [ref-coords resolution] :as options}]
  {:pre [(s/valid? ::pg/polygon polygon)
         (s/valid? string? output-path)
         (s/valid? ::rasterize-options options)]}
  (let [srs            (srs-from-pg polygon)
        geometry       (pg->geometry polygon)
        envelope       (envelope geometry)
        aligned-extent (compute-aligned-raster-extent envelope ref-coords resolution)
        datasource     (build-datasource-from-geometry srs geometry)
        layer          (.GetLayer datasource 0)
        raster         (build-mask-raster-file output-path (assoc aligned-extent :srs srs))]
    (gdal/RasterizeLayer raster
                         (int-array [1])
                         layer
                         (double-array [255.0]))
    (.FlushCache raster)

    ;; Cleanup GDAL objects
    (.delete raster)
    (.delete datasource)
    (.delete srs)))

(comment
  (require '[planwise.boundary.coverage :as coverage])
  (def pg (coverage/compute-coverage (:planwise.component/coverage integrant.repl.state/system)
                                     {:lat -3.0361 :lon 40.1333}
                                     {:algorithm :pgrouting-alpha
                                      :driving-time 60}))

  ;; (def ref-coords {:lat 5.470694601152364 :lon 33.912608425216725})
  (def ref-coords {:lon 39.727577500000002 :lat -2.631561400000000})
  ;; (def pixel-resolution {:x-res 8.333000000000001E-4 :y-res 8.333000000000001E-4})
  (def pixel-resolution {:x-res 1/1200 :y-res 1/1200})

  (rasterize pg "test.tif" {:ref-coords ref-coords :resolution pixel-resolution}))