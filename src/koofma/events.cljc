(ns koofma.events
  "The single funnel through which every state change flows. Handlers are
  pure: (handle db event now-ms) -> db'. HLC stamps are minted here and
  nowhere else; one event = one stamp."
  (:require
    [koofma.crdt :as crdt]
    [koofma.hlc :as hlc]
    [koofma.model :as model]))


(defn- stamp
  "Advance the db's clock for one event; returns [db' stamp]."
  [db now-ms]
  (let [clock (hlc/tick (:clock db) now-ms)]
    [(assoc db :clock clock) clock]))


(defmulti handle (fn [_db [event-id] _now-ms] event-id))


(defmethod handle :default [_db [event-id] _now]
  (throw (ex-info "Unknown event" {:event event-id})))


(defmethod handle :item/add
  [db [_ {:keys [id title]}] now]
  (let [[db' t] (stamp db now)
        id      (or id (random-uuid))
        rank    (model/rank-at-end (:doc db))]
    (update db' :doc model/add-item id title rank t)))


(defmethod handle :item/set-title
  [db [_ {:keys [id title]}] now]
  (let [[db' t] (stamp db now)]
    (update db' :doc model/set-title id title t)))


(defmethod handle :item/check
  [db [_ {:keys [id]}] now]
  (let [title   (model/fval (get-in db [:doc :items id]) :title)
        [db' t] (stamp db now)]
    (-> db'
        (update :doc model/delete-item id t)
        (assoc :flash {:type :undo-check :id id :title title}))))


(defmethod handle :item/undelete
  [db [_ {:keys [id]}] now]
  (let [[db' t] (stamp db now)]
    (update db' :doc model/undelete-item id t)))


(defmethod handle :item/move
  [db [_ {:keys [id target-id after?]}] now]
  (if-let [rank (model/rank-for-drop (:doc db) id target-id after?)]
    (let [[db' t] (stamp db now)]
      (update db' :doc model/move-item id rank t))
    db))


(defmethod handle :remote/merged
  [db [_ {:keys [doc]}] now]
  (let [merged (crdt/merge-docs (:doc db) doc)
        clock  (if-let [remote-t (crdt/latest-stamp doc)]
                 (hlc/recv (:clock db) remote-t now)
                 (:clock db))]
    (assoc db :doc merged :clock clock)))


;; Remote config is settings-screen state (S3/R2 spec), not part of the CRDT
;; doc, so these don't stamp the clock either.

(defmethod handle :remote/configure
  [db [_ {:keys [spec]}] _]
  (assoc db :remote-config spec))


(defmethod handle :remote/disconnect
  [db _ _]
  (-> db (dissoc :remote-config) (assoc :sync-state :not-configured)))


(defmethod handle :flash/clear
  [db _ _]
  (dissoc db :flash))


;; UI-only state (not part of the CRDT doc): no clock stamp.

(defmethod handle :ui/edit-item
  [db [_ {:keys [id]}] _]
  (assoc db :editing-id id))


(defmethod handle :ui/stop-editing
  [db _ _]
  (dissoc db :editing-id))


(defmethod handle :ui/drag-start
  [db [_ {:keys [id]}] _]
  (assoc db :drag {:id id}))


(defmethod handle :ui/drag-over
  [db [_ {:keys [target-id after?]}] _]
  (if (:drag db)
    (assoc-in db [:drag :over] {:target-id target-id :after? after?})
    db))


(defmethod handle :ui/drag-end
  [db _ _]
  (dissoc db :drag))


;; Sync state is ephemeral, same no-stamp shape.

(defmethod handle :sync/start
  [db _ _]
  (assoc db :sync-state :syncing))


(defmethod handle :sync/success
  [db _ _]
  (-> db (assoc :sync-state :idle) (dissoc :sync-error)))


(defmethod handle :sync/error
  [db [_ {:keys [error]}] _]
  (assoc db :sync-state :error :sync-error error))


(defmethod handle :sync/offline
  [db _ _]
  (assoc db :sync-state :offline))


;; A new service worker installed and is waiting to activate.

(defmethod handle :sw/update-available
  [db _ _]
  (assoc db :flash {:type :sw-update}))
