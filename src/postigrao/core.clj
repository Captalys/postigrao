(ns postigrao.core
  (:require [postigrao.services.database :refer [datasource]]
            [chime.core :as chime]
            [clojure.core.async :as a]
            [clojure.string :as cstr]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql])
  (:import [java.time Instant]))

(def dataset-size 10000)

(def dataset
  (let [users (range 1 dataset-size)
        amounts (repeatedly dataset-size #(rand-int 80000))]
    (map #(hash-map :user_id %1  :amount %2) users amounts)))

(defn load-data
  "Load data into `schedule_transfer` table."
  [data]
  (jdbc/with-transaction [tx datasource]
    (let [columns (keys (first data))
          values (map vals data)]
      (sql/insert-multi! tx "schedule_transfer" columns values))))

(def qq "update schedule_transfer set state = 'DOING'
where schedule_transfer.id in (select id from schedule_transfer where state = 'PENDING' limit 1000)
returning user_id, amount")

(defn get-data-not-protected []
  (jdbc/with-transaction [tx datasource]
    (sql/query tx [qq])))

(defn insert-balance-table [data]
  (let [columns (list :user_id :amount)
        values (map vals data)]
    (jdbc/with-transaction [tx datasource]
      (sql/insert-multi! tx "balance" columns values))))

(defn NOT-PROTECTED-processing []
  (-> (get-data-not-protected)
      insert-balance-table))

(def qp "update schedule_transfer set state = 'DOING'
where schedule_transfer.id in (select id from schedule_transfer where state = 'PENDING' for update skip locked limit 100)
returning user_id, amount")

(defn get-data-protected []
  (jdbc/with-transaction [tx datasource]
    (sql/query tx [qp])))

(defn PROTECTED-processing []
  (-> (get-data-protected)
      insert-balance-table))

(defn clear-databases []
  (jdbc/with-transaction [tx datasource]
    (sql/query tx ["truncate table schedule_transfer"])
    (sql/query tx ["truncate table balance"])))

(declare parallel-processes)

(defn schedule-runner [now custom-fn]
  "Each runner will be executing in 15 secs from `now`"
  (chime/chime-at [(.plusSeconds now 10)]
                  (fn [time]
                    (custom-fn))
                  {:on-finished (fn [] (println "Done"))}))

(defn deploy-runners-in-parallel [runner]
  (let [now (Instant/now)]
    (dotimes [_ parallel-processes]
      (a/go (schedule-runner now runner)))))

(defn amount-of-rows-in-balance-must-be-the-same-as-DOING []
  (jdbc/with-transaction [tx datasource]
    (let [ret1 (first (sql/query tx ["select count(*) as ct from schedule_transfer where state = 'DOING'"]))
          ret2 (first (sql/query tx ["select count(*) as ct from balance"]))]
      {:number-rows-schedule (:ct ret1)
       :number-rows-balance (:ct ret2)
       :error? (not (= (:ct ret1) (:ct ret2)))})))

(comment
  ;; 0.0 Start the application
  (user/start)

  ;; 0 Perform migrations
  (postigrao.migrations/apply-migrations)

  ;; 1. load the database with dummy data (I called this fn 3 times to generate enough data)
  (load-data dataset)

  ;; 2. define number of threads
  (def parallel-processes 10000)

  ;; 3. test everything not protected
  (deploy-runners-in-parallel NOT-PROTECTED-processing)

  ;; FROM LOGS
  ;; db_1    | 2020-05-25 22:54:48.989 UTC [378] ERROR:  deadlock detected
;; db_1    | 2020-05-25 22:54:48.989 UTC [378] DETAIL:  Process 378 waits for ExclusiveLock on tuple (1465,115) of relation 24621 of database 16386; blocked by process 381.
;; db_1    | 	Process 381 waits for ShareLock on transaction 12576; blocked by process 375.
;; db_1    | 	Process 375 waits for ShareLock on transaction 12602; blocked by process 376.
;; db_1    | 	Process 376 waits for ShareLock on transaction 12468; blocked by process 378.
;; db_1    | 	Process 378: update schedule_transfer set state = 'DOING'
;; db_1    | 	where schedule_transfer.id in (select id from schedule_transfer limit 1000)
;; db_1    | 	returning user_id, amount
;; db_1    | 	Process 381: update schedule_transfer set state = 'DOING'
;; db_1    | 	where schedule_transfer.id in (select id from schedule_transfer limit 1000)
;; db_1    | 	returning user_id, amount
;; db_1    | 	Process 375: update schedule_transfer set state = 'DOING'
;; db_1    | 	where schedule_transfer.id in (select id from schedule_transfer limit 1000)
;; db_1    | 	returning user_id, amount
;; db_1    | 	Process 376: update schedule_transfer set state = 'DOING'
;; db_1    | 	where schedule_transfer.id in (select id from schedule_transfer limit 1000)
;; db_1    | 	returning user_id, amount
;; db_1    | 2020-05-25 22:54:48.989 UTC [378] HINT:  See server log for query details.
;; db_1    | 2020-05-25 22:54:48.989 UTC [378] STATEMENT:  update schedule_transfer set state = 'DOING'
;; db_1    | 	where schedule_transfer.id in (select id from schedule_transfer limit 1000)
;; db_1    | 	returning user_id, amount

  (amount-of-rows-in-balance-must-be-the-same-as-DOING)
  ;; => {:number-rows-schedule 29997, :number-rows-balance 111988, :error? true}

  ;; 4. clear the whole database
  (clear-databases)

  ;; 5. reload the database with dummy data
  (load-data dataset)

  ;; 6. test everything protected
  (deploy-runners-in-parallel PROTECTED-processing)

  (amount-of-rows-in-balance-must-be-the-same-as-DOING)
  ;; => {:number-rows-schedule 29997, :number-rows-balance 29997, :error? false}

  ;; cqd
  )
