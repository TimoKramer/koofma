(ns koofma.persist
  "IndexedDB durability via konserve. Read once at boot, write-behind after
  dispatches; app-db stays the runtime source of truth."
  (:require
    [clojure.core.async :refer [go <!]]
    [koofma.hlc :as hlc]
    [koofma.model :as model]
    [konserve.core :as k]
    [konserve.indexeddb :refer [connect-idb-store]]))


(def db-name "koofma")

(def persisted-keys [:doc :clock :remote-config])


(defn connect
  []
  (connect-idb-store db-name))


(defn load-db
  "Channel with the initial app-db; mints node-id and clock on first run."
  [store]
  (go
    (let [node-id (or (<! (k/get store :node-id nil {:sync? false}))
                      (let [id (str (random-uuid))]
                        (<! (k/assoc store :node-id id {:sync? false}))
                        id))]
      {:node-id       node-id
       :doc           (or (<! (k/get store :doc nil {:sync? false}))
                          model/empty-doc)
       :clock         (or (<! (k/get store :clock nil {:sync? false}))
                          (hlc/init node-id))
       ;; the S3/R2 spec entered in the settings screen, if any — koofma.main
       ;; reconnects with it on boot; :sync-state itself is not persisted.
       :remote-config (<! (k/get store :remote-config nil {:sync? false}))
       :sync-state    :not-configured})))


(defn save-changed!
  "Write-behind: persist only the keys an event actually changed."
  [store before after]
  (go
    (doseq [key persisted-keys
            :when (not= (get before key) (get after key))]
      (<! (k/assoc store key (get after key) {:sync? false})))))
