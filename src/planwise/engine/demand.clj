(ns planwise.engine.demand
  (:require [clojure.spec.alpha :as s]
            [planwise.engine.raster :as raster])
  (:import [planwise.engine Algorithm]
           [org.gdal.gdalconst gdalconst]))

(defn count-population
  [population]
  (let [{:keys [data nodata]} population]
    (Algorithm/countPopulation data nodata)))

(defn get-coverage
  "Retrieves pixel index under coverage"
  [population coverage]
  (let [{src-bounds :src dst-bounds :dst}       (raster/clipped-coordinates population coverage)
        {src-buffer :data src-nodata :nodata}   coverage
        {dst-buffer :data dst-nodata :nodata}   population
        [dst-left dst-top dst-right dst-bottom] dst-bounds
        [src-left src-top _ _]                  src-bounds
        dst-stride                              (:xsize population)
        src-stride                              (:xsize coverage)]
    (Algorithm/getPointsOfCoverage dst-buffer dst-stride dst-nodata
                                   src-buffer src-stride src-nodata
                                   dst-left dst-top dst-right dst-bottom
                                   src-left src-top)))

(defn count-population-under-coverage
  "Count population pixel values under coverage"
  [population coverage]
  (let [{src-bounds :src dst-bounds :dst}       (raster/clipped-coordinates population coverage)
        {src-buffer :data src-nodata :nodata}   coverage
        {dst-buffer :data dst-nodata :nodata}   population
        [dst-left dst-top dst-right dst-bottom] dst-bounds
        [src-left src-top _ _]                  src-bounds
        dst-stride                              (:xsize population)
        src-stride                              (:xsize coverage)]
    (Algorithm/countPopulationUnderCoverage dst-buffer dst-stride dst-nodata
                                            src-buffer src-stride src-nodata
                                            dst-left dst-top dst-right dst-bottom
                                            src-left src-top)))

(defn multiply-population!
  [population factor]
  (let [{:keys [data nodata]} population]
    (Algorithm/multiplyPopulation data nodata factor)))

(defn multiply-population-under-coverage!
  "Multiply each population pixel value by factor if under coverage"
  [population coverage factor]
  (let [{src-bounds :src dst-bounds :dst}       (raster/clipped-coordinates population coverage)
        {src-buffer :data src-nodata :nodata}   coverage
        {dst-buffer :data dst-nodata :nodata}   population
        [dst-left dst-top dst-right dst-bottom] dst-bounds
        [src-left src-top _ _]                  src-bounds
        dst-stride                              (:xsize population)
        src-stride                              (:xsize coverage)]
    (Algorithm/multiplyPopulationUnderCoverage dst-buffer dst-stride dst-nodata
                                               src-buffer src-stride src-nodata
                                               factor
                                               dst-left dst-top dst-right dst-bottom
                                               src-left src-top)))

(defn compute-population-quartiles
  [population]
  (vec (Algorithm/computeExactQuartiles (:data population) (:nodata population))))

(defn build-renderable-population
  [population quartiles]
  (let [pixel-count (count (:data population))
        pixels      (byte-array pixel-count)
        ;; Java  bytes are signed, so this should be -1, but we write 255 for
        ;; consistency with Mapserver style definition
        nodata      (unchecked-byte 255)]
    (Algorithm/mapDataForRender (:data population) (:nodata population) pixels nodata (float-array quartiles))
    (raster/map->Raster (assoc population
                               :data pixels
                               :nodata nodata
                               :data-type gdalconst/GDT_Byte))))

(defn find-max-demand
  [demand]
  (let [index (Algorithm/findMaxIndex (:data demand) (:nodata demand))]
    (when (> index 0)
      (let [xsize                     (:xsize demand)
            x                         (mod index xsize)
            y                         (Math/floor (float (/ index xsize)))
            [xoff xres _ yoff _ yres] (:geotransform demand)
            lon                       (+ xoff (* xres x))
            lat                       (+ yoff (* yres y))
            value                     (aget (:data demand) index)]
        {:lon   lon
         :lat   lat
         :index index
         :value value}))))


(comment
  (def raster1 (raster/read-raster "data/populations/data/20/42.tif"))
  (def raster2 (raster/read-raster "data/coverage/11/1_pgrouting-alpha_60.tif"))

  (time (count-population raster1))
  (time (multiply-population! raster1 0.5))

  (time (count-population-under-coverage raster1 raster2))
  (time (multiply-population-under-coverage! raster1 raster2 0.9))

  (def quartiles (compute-population-quartiles raster1))
  (def renderable (build-renderable-population raster1 quartiles))

  (raster/write-raster renderable "data/test_scenario.tif"))
