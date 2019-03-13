(ns planwise.util.geo
  (:require [clojure.spec.alpha :as s])
  (:import [org.postgis PGgeometry]
           [org.gdal.ogr ogr Geometry]
           [org.gdal.osr SpatialReference]))

(s/def ::lat number?)
(s/def ::lon number?)
(s/def ::coords (s/keys :req-un [::lat ::lon]))

(s/def ::min-lat ::lat)
(s/def ::max-lat ::lat)
(s/def ::min-lon ::lon)
(s/def ::max-lon ::lon)
(s/def ::envelope (s/keys :req-un [::min-lat ::min-lon ::max-lat ::max-lon]))

(s/def ::pg-geometry #(instance? PGgeometry %))
(s/def ::pg-point   (s/and ::geometry #(= org.postgis.Geometry/POINT   (.getGeoType %))))
(s/def ::pg-polygon (s/and ::geometry #(= org.postgis.Geometry/POLYGON (.getGeoType %))))

(defn make-pg-point*
  [lat lon]
  (PGgeometry. (str "SRID=4326;POINT(" lon " " lat ")")))

(defn make-pg-point
  ([{:keys [lat lon] :as coords}]
   {:pre [(s/valid? ::coords coords)]}
   (make-pg-point lat lon))
  ([lat lon]
   {:pre [(s/valid? ::lat lat)
          (s/valid? ::lon lon)]
    :post [(s/valid? ::pg-point %)]}
   (make-pg-point* lat lon)))

(defn epsg->srs
  "Returns a GDAL SpatialReference from the EPSG code"
  [epsg]
  (doto (SpatialReference.)
    (.ImportFromEPSG epsg)))

(defn pg->srs
  "Returns a GDAL SpatialReference from the a PostGIS geometry"
  [pg]
  (let [srid (.. pg (getGeometry) (getSrid))]
    (epsg->srs srid)))

(defn pg->geometry
  "Creates a GDAL Geometry from a PostGIS geometry"
  [pg]
  (let [wkt (second (PGgeometry/splitSRID (str pg)))]
    (Geometry/CreateFromWkt wkt)))

(defn geometry->envelope
  [geom]
  (let [env (double-array 4)]
    (.GetEnvelope geom env)
    (let [[min-lon max-lon min-lat max-lat] env]
      {:min-lon min-lon
       :min-lat min-lat
       :max-lon max-lon
       :max-lat max-lat})))
