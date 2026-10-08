(ns koofma.views
  "Pure hiccup builders for replicant.dom/render. No dispatching here — every
  interactive element emits data actions resolved by koofma.main's
  execute-actions!/resolve-placeholder, keeping this namespace a straight fn
  of app-db. Markup is Tailwind utilities + daisyUI components."
  (:require
    [koofma.model :as model]))


(defn- dispatch
  [event]
  [[:action/dispatch event]])


(defn- item-title
  "Tap to edit; an input while editing? (commits on Enter/blur, discards on
  Escape)."
  [id title editing?]
  (if editing?
    [:input.input.input-ghost.input-sm.flex-1.w-full
     {:value title :autofocus true
      :on {:keydown [[:action/set-title id :event/key :event/target.value]]
           :blur    [[:action/set-title id "blur" :event/target.value]]}}]
    [:span.flex-1.select-none.cursor-text
     {:on {:click (dispatch [:ui/edit-item {:id id}])}}
     title]))


(defn- item-row
  [[id item] editing? {:keys [drag]}]
  (let [dragging? (= id (:id drag))
        over      (:over drag)
        over?     (and drag (not dragging?) (= id (:target-id over)))]
    [:li.item.flex.items-center.gap-2.rounded-box.px-3.py-2.border-y-2.border-transparent
     {:replicant/key id
      :data-id       (str id)
      :class         [(when dragging? "opacity-40")
                      "bg-base-200/60"
                      (when over? (if (:after? over) "border-b-primary" "border-t-primary"))]}
     [:span.drag-handle.select-none.opacity-50.px-1
      {:title "Drag to reorder"
       :on {:pointerdown [[:action/drag-start id]]}}
      "⠿"]
     [:input.checkbox.checkbox-sm
      {:type "checkbox" :checked false
       :aria-label (str "Check off " (model/fval item :title))
       :on {:change (dispatch [:item/check {:id id}])}}]
     (item-title id (model/fval item :title) editing?)]))


(defn- quick-add
  [suggestions]
  (list
    [:input.input.input-bordered.w-full.mb-3
     {:type "text" :placeholder "Add an item…" :list "suggestions"
      :autocomplete "off" :autofocus true
      :on {:keydown [[:action/add-item :event/key :event/target.value :event/target]]}}]
    [:datalist#suggestions
     (for [title suggestions]
       [:option {:replicant/key title :value title}])]))


(defn- flash-toast
  "undo-check auto-dismisses after 5 s (see koofma.main's ::flash-auto-dismiss
  watch); sw-update sticks around until acted on."
  [{:keys [type id title]}]
  (when type
    [:div.toast.toast-end.toast-bottom {:replicant/key (or id type)}
     [:div.alert {:class (if (= type :sw-update) "alert-success" "alert-info")}
      [:span (case type
               :undo-check (str "Checked off \"" title "\"")
               :sw-update "A new version of koofma is available.")]
      (when (= type :undo-check)
        [:button.btn.btn-sm {:on {:click [[:action/undo-check id]]}} "Undo"])
      (when (= type :sw-update)
        [:button.btn.btn-sm {:on {:click [[:action/reload-for-update]]}} "Reload"])
      [:button.btn.btn-ghost.btn-sm.btn-circle
       {:on {:click [[:action/dismiss-flash]]}} "✕"]]]))


(defn- sync-indicator
  [sync-state]
  [:span.badge
   {:class (case sync-state
             :idle "badge-success"
             :syncing "badge-warning"
             :error "badge-error"
             "badge-ghost")}
   (name sync-state)])


(defn- connect-form
  []
  [:form.flex.flex-col.gap-2
   {:on {:submit [[:action/configure-remote :event/target]]}}
   [:input.input.input-bordered
    {:name "endpoint" :required true
     :placeholder "Endpoint (https://<account>.r2.cloudflarestorage.com)"}]
   [:input.input.input-bordered {:name "bucket" :required true :placeholder "Bucket"}]
   [:input.input.input-bordered {:name "access-key" :required true :placeholder "Access key"}]
   [:input.input.input-bordered
    {:name "secret" :type "password" :required true :placeholder "Secret"}]
   [:input.input.input-bordered
    {:name "id" :placeholder "Store id — same value for both users (blank = generate new)"}]
   [:button.btn.btn-primary.mt-2 {:type "submit"} "Connect"]])


(defn- connected-panel
  [{:keys [bucket id]} sync-state sync-error]
  [:div.flex.flex-col.gap-3
   [:div.text-sm.opacity-70 (str "bucket \"" bucket "\" · store " id)]
   (sync-indicator sync-state)
   (when (and (= sync-state :error) sync-error)
     [:div.text-sm.text-error (.-message sync-error)])
   [:button.btn.btn-error.btn-sm.self-start
    {:on {:click [[:action/disconnect-remote]]}} "Disconnect"]])


(defn- settings-dialog
  [remote-config sync-state sync-error]
  [:dialog#settings-dialog.modal
   [:div.modal-box
    [:h3.font-bold.text-lg.mb-4 "Sync settings"]
    (if remote-config
      (connected-panel remote-config sync-state sync-error)
      (connect-form))
    [:div.modal-action
     [:button.btn {:on {:click [[:action/close-settings]]}} "Close"]]]])


(defn app-view
  [{:keys [doc flash sync-state sync-error remote-config editing-id drag] :as db}]
  [:div.max-w-xl.mx-auto.p-4
   [:div.navbar.bg-base-200.rounded-box.mb-4
    [:div.flex-1 [:h1.text-xl.font-bold.px-2 "koofma"]]
    [:div.flex-none.gap-2.px-2
     (sync-indicator sync-state)
     [:button.btn.btn-ghost.btn-circle.btn-sm
      {:on {:click [[:action/open-settings]]}} "⚙"]]]
   (quick-add (model/suggestions doc))
   [:ul#items.flex.flex-col.gap-1
    (for [[id :as entry] (model/items doc)]
      (item-row entry (= editing-id id) db))]
   (when (empty? (model/items doc))
     [:p.text-center.opacity-60.mt-8 "Nothing to buy."])
   (flash-toast flash)
   (settings-dialog remote-config sync-state sync-error)])
