(ns planwise.config
  (:require [environ.core :refer [env]]))

(def defaults
  ^:displace {:http {:port 3000}
              :auth {:guisso-url "https://login.instedd.org"}})

(def environ
  {:http {:port                 (some-> env :port Integer.)}
   :db   {:uri                  (env :database-url)}
   :auth {; Base Guisso URL
          :guisso-url           (env :guisso-url)
          ; Guisso credentials for OAuth2 authentication
          :guisso-client-id     (env :guisso-client-id)
          :guisso-client-secret (env :guisso-client-secret)}})
