(ns planwise.client.scenarios.api)

(defn- load-scenario
  [id]
  {:method    :get
   :uri       (str "/api/scenarios/" id)})

(defn- copy-scenario
  [id]
  {:method    :post
   :uri       (str "/api/scenarios/" id "/copy")})

(defn- update-scenario
  [id scenario]
  {:method    :put
   :params    {:scenario scenario}
   :uri       (str "/api/scenarios/" id)})

(defn- load-scenarios
  [id]
  {:method    :get
   :uri       (str "/api/projects2/" id "/scenarios")})

(defn- suggested-providers
  [id]
  {:method    :get
   :timeout   90000
   :uri       (str "/api/scenarios/" id "/suggested-providers")})

(defn- get-providers-geom
  [id]
  :method :get
  :uri    (str "/api/scenarios/" id "/geometries"))
