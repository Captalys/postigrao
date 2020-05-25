(ns postigrao.migrations
  (:require [postigrao.services.config :refer [config]]
            [migratus.core :as migratus]
            [mount.core :as mount]))

(defn- start []
  (let [db (:postgresql config)
        mig-cfg {:store :database
                 :migration-dir "migrations/"
                 :init-in-transaction? false
                 :migration-table-name "migrations"
                 :db db}]
    mig-cfg))

(mount/defstate migration-cfg
  :start (start)
  :stop identity)

(defn create-migrations
  "Create two migration files, UP and DOWN, these code need to be written manually.

  :filename   prefix-name for the files to be created"
  [filename]
  (migratus/create migration-cfg filename))

(defn apply-migrations
  "Apply all the pending migrations in the connected database"
  []
  (migratus/migrate migration-cfg))


(defn rollback-migrations
  "Rollback all the migrations done previously"
  []
  (migratus/rollback migration-cfg))

(defn reset
  "Reset all the migrations"
  []
  (migratus/reset migration-cfg))
