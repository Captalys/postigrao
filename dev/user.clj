(ns user
  (:require [mount.core :as mount]
            [clojure.tools.namespace.repl :as tn]
            [postigrao.services.config :as config]
            [postigrao.migrations :as migrations]
            [postigrao.services.database :as database]))

(defn start []
  (mount/start #'config/config
               #'database/datasource
               #'migrations/migration-cfg))

(defn stop []
  (mount/stop))

(defn refresh []
  (stop)
  (tn/refresh))

(defn go []
  (start)
  :ready)

(defn reset []
  (stop)
  (tn/refresh :after 'user/go))
