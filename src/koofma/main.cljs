(ns koofma.main
  (:require
    [clojure.core.async :refer [go <!]]
    [clojure.string :as str]
    [koofma.app :as app]
    [koofma.persist :as persist]
    [koofma.sync :as sync]
    [koofma.views :as views]
    [replicant.dom :as r]))


;; Composition root: the one global reference, required by shadow-cljs's
;; init-fn/after-load hooks and handy at the REPL:
;;   (-> @koofma.main/!system :app-db deref)
(defonce !system (atom nil))


;; Debounce id for the after-write sync trigger; survives hot-reload like
;; !system does.
(defonce reconcile-timeout (atom nil))


;; Auto-dismiss id for the undo toast; survives hot-reload like !system does.
(defonce flash-timeout (atom nil))
(def flash-timeout-ms 5000)


(defn- reconcile-if-configured!
  [system]
  (when-let [remote-store @(:remote system)]
    (sync/reconcile! system remote-store)))


(defn- debounced-reconcile!
  "Coalesces bursts of writes into one reconcile call ~1s after the last one."
  [system]
  (when @(:remote system)
    (some-> @reconcile-timeout js/clearTimeout)
    (reset! reconcile-timeout
            (js/setTimeout #(reconcile-if-configured! system) 1000))))


(defn configure-remote!
  "Connect this session to a remote store, remember the spec for next boot
  (see koofma.persist), and switch on sync. Callable from the settings screen,
  or the browser console/REPL, e.g.
  (koofma.main/configure-remote!
    {:endpoint \"https://<account>.r2.cloudflarestorage.com\"
     :bucket \"koofma\" :access-key \"…\" :secret \"…\" :id \"<store-uuid>\"})
  Connects, reconciles once immediately, then the focus/online/debounced-
  write triggers registered in init! take over for the rest of the session."
  [s3-spec]
  (go
    (let [system       @!system
          remote-store (<! (sync/connect s3-spec))]
      (if (instance? js/Error remote-store)
        (app/dispatch! system [:sync/error {:error remote-store}])
        (do (reset! (:remote system) remote-store)
            (app/dispatch! system [:remote/configure {:spec s3-spec}])
            (<! (sync/reconcile! system remote-store)))))))


(defn disconnect-remote!
  "Forgets the remote config and switches sync off; the next boot won't
  auto-reconnect."
  []
  (let [system @!system]
    (reset! (:remote system) nil)
    (app/dispatch! system [:remote/disconnect])))



(defn- render!
  [{:keys [app-db]}]
  (when-let [el (js/document.getElementById "app")]
    (r/render el (views/app-view @app-db))))


(defn- resolve-placeholder
  "Substitutes koofma.views' :event/... placeholders with the live DOM
  event's data at dispatch time (Replicant does not do this for you — see
  https://replicant.fun/event-handlers/)."
  [dom-event x]
  (case x
    :event/key          (.-key dom-event)
    :event/target       (.-target dom-event)
    :event/target.value (.. dom-event -target -value)
    x))


(defn- execute-actions!
  [system dom-event actions]
  (doseq [[action & args] actions
          :let [args (map (partial resolve-placeholder dom-event) args)]]
    (case action
      :action/dispatch
      (let [[event] args]
        (app/dispatch! system event))

      :action/add-item
      (let [[key title target] args]
        (when (and (= key "Enter") (seq (str/trim title)))
          (app/dispatch! system [:item/add {:title (str/trim title)}])
          (set! (.-value target) "")))

      :action/set-title
      (let [[id key title] args]
        (case key
          ("Enter" "blur")
          (do (when (seq (str/trim title))
                (app/dispatch! system [:item/set-title {:id id :title (str/trim title)}]))
              (app/dispatch! system [:ui/stop-editing]))
          "Escape" (app/dispatch! system [:ui/stop-editing])
          nil))

      :action/drag-start
      (let [[id] args]
        (.preventDefault dom-event)
        (app/dispatch! system [:ui/drag-start {:id id}]))

      :action/undo-check
      (let [[id] args]
        (some-> @flash-timeout js/clearTimeout)
        (app/dispatch! system [:item/undelete {:id id}])
        (app/dispatch! system [:flash/clear]))

      :action/dismiss-flash
      (do (some-> @flash-timeout js/clearTimeout)
          (app/dispatch! system [:flash/clear]))

      :action/configure-remote
      (let [[form] args
            field  #(.. form -elements (namedItem %) -value)
            id     (field "id")]
        (configure-remote! {:endpoint   (field "endpoint")
                            :bucket     (field "bucket")
                            :access-key (field "access-key")
                            :secret     (field "secret")
                            :region     "auto"
                            :path-style? true
                            :id         (if (seq id) id (str (random-uuid)))}))

      :action/disconnect-remote
      (disconnect-remote!)

      :action/open-settings
      (some-> (js/document.getElementById "settings-dialog") .showModal)

      :action/close-settings
      (some-> (js/document.getElementById "settings-dialog") .close)

      :action/reload-for-update
      ;; the controllerchange listener (see register-service-worker!) does the
      ;; actual reload once the waiting worker takes over.
      (some-> (.. js/navigator -serviceWorker -controller) (.postMessage "SKIP_WAITING")))))


;; Drag-and-drop with pointer events (works for touch and mouse; HTML5 DnD
;; does not on touch). The handle's pointerdown starts a drag in app-db; these
;; document-level listeners track the pointer, mark the row under it and drop.

(defn- item-id-at
  "[id after?] for the item row under the point (x, y), or nil."
  [system x y]
  (when-let [row (some-> (js/document.elementFromPoint x y) (.closest "li.item"))]
    (let [rect (.getBoundingClientRect row)
          sid  (.getAttribute row "data-id")
          id   (some #(when (= sid (str %)) %) (keys (:items (:doc @(:app-db system)))))]
      (when id [id (> y (+ (.-top rect) (/ (.-height rect) 2)))]))))


(defn- on-pointer-move
  [system e]
  (when (:drag @(:app-db system))
    (when-let [[target-id after?] (item-id-at system (.-clientX e) (.-clientY e))]
      (app/dispatch! system [:ui/drag-over {:target-id target-id :after? after?}]))))


(defn- on-pointer-up
  [system _e]
  (when-let [{:keys [id over]} (:drag @(:app-db system))]
    (when over
      (app/dispatch! system [:item/move (assoc over :id id)]))
    (app/dispatch! system [:ui/drag-end])))


(defn- register-service-worker!
  "Registers the app-shell service worker (see public/sw.js) and wires the
  update flow: when a new worker finishes installing behind an already-
  active one, surface a :sw/update-available toast (see koofma.events);
  reloading happens once the new worker actually takes control, not on
  click, so the reload always sees the new code. Skipped in dev — shadow-
  cljs's watch build explodes into dozens of small files that change on
  every save, which the cache-first strategy would happily go stale on."
  [system]
  (when (and (not js/goog.DEBUG) (.. js/navigator -serviceWorker))
    (.addEventListener (.. js/navigator -serviceWorker) "controllerchange"
                       #(.reload js/location))
    ;; relative — registers at (and scopes to) wherever this page itself is
    ;; served, root or subpath, without needing to know which in advance.
    (-> (.register (.. js/navigator -serviceWorker) "./sw.js")
        (.then (fn [registration]
                 (.addEventListener registration "updatefound"
                                    (fn []
                                      (let [installing (.-installing registration)]
                                        (.addEventListener installing "statechange"
                                                           (fn []
                                                             (when (and (= "installed" (.-state installing))
                                                                        (.. js/navigator -serviceWorker -controller))
                                                               (app/dispatch! system [:sw/update-available]))))))))))))



(defn init!
  []
  (go
    (let [store  (<! (persist/connect))
          system (app/new-system store (<! (persist/load-db store)))]
      (reset! !system system)
      (r/set-dispatch! (fn [{:replicant/keys [dom-event]} actions]
                         ;; submit: don't navigate/reload
                         (when (= "submit" (.-type dom-event))
                           (.preventDefault dom-event))
                         (execute-actions! system dom-event actions)))
      (when-let [spec (:remote-config @(:app-db system))]
        (configure-remote! spec))
      (add-watch (:app-db system) ::render (fn [_ _ _ _] (render! system)))
      (add-watch (:app-db system) ::flash-auto-dismiss
                 (fn [_ _ before after]
                   (when (and (:flash after) (not= (:flash before) (:flash after))
                              (not= :sw-update (:type (:flash after))))
                     (some-> @flash-timeout js/clearTimeout)
                     (reset! flash-timeout
                             (js/setTimeout #(app/dispatch! system [:flash/clear])
                                            flash-timeout-ms)))))
      (add-watch (:app-db system) ::reconcile-on-write
                 (fn [_ _ before after]
                   (when (not= (:doc before) (:doc after))
                     (debounced-reconcile! system))))
      (.addEventListener js/document "pointermove" #(on-pointer-move system %))
      (.addEventListener js/document "pointerup" #(on-pointer-up system %))
      (.addEventListener js/document "pointercancel" #(app/dispatch! system [:ui/drag-end]))
      ;; pull the partner's changes when the app regains focus
      (.addEventListener js/window "focus" #(reconcile-if-configured! system))
      (.addEventListener js/window "online" #(reconcile-if-configured! system))
      (.addEventListener js/window "offline" #(app/dispatch! system [:sync/offline]))
      (register-service-worker! system)
      ;; iOS may evict IndexedDB after ~7 days unused; best-effort — the
      ;; durability backstop is the bucket, not this grant.
      (when-let [storage (.. js/navigator -storage)]
        (when (.-persist storage) (.persist storage)))
      (render! system)
      (js/console.log "koofma booted — state: (-> @koofma.main/!system :app-db deref)"))))


(defn reload!
  []
  (some-> @!system render!))
