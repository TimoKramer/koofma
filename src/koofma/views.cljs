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
  "A real form so the browser offers (and records) its own autofill history
  for the field. The submit goes to a hidden iframe, which counts as a genuine
  submission for the browser but keeps the page where it is — see
  koofma.main/execute-actions! for why this form's submit is not
  preventDefault'ed."
  []
  (list
    [:iframe {:name "form-sink" :hidden true :tab-index -1}]
    [:form#add-form.mb-3
     {:method "post" :action "about:blank" :target "form-sink" :autocomplete "on"
      :on {:submit [[:action/add-item :event/target]]}}
     [:input.input.input-bordered.w-full
      {:type "text" :name "item" :placeholder "Add an item…" :autofocus true
       :autocomplete "on" :autocapitalize "sentences" :enterkeyhint "done"}]]))


(defn- flash-toast
  "undo-check auto-dismisses after 5 s (see koofma.main's ::flash-auto-dismiss
  watch); sw-update sticks around until acted on."
  [{:keys [type id title undo]}]
  (when type
    [:div.toast.toast-end.toast-bottom {:replicant/key (or id type)}
     [:div.alert {:class (case type
                           :sw-update "alert-success"
                           :duplicate "alert-warning"
                           "alert-info")}
      [:span (case type
               :duplicate (str "\"" title "\" is already on the list")
               :undo-check (str "Checked off \"" title "\"")
               :undo-list-delete (str "Deleted list \"" title "\"")
               :sw-update "A new version of koofma is available.")]
      (when undo
        [:button.btn.btn-sm {:on {:click [[:action/undo undo]]}} "Undo"])
      (when (= type :sw-update)
        [:button.btn.btn-sm {:on {:click [[:action/reload-for-update]]}} "Reload"])
      [:button.btn.btn-ghost.btn-sm.btn-circle
       {:on {:click [[:action/dismiss-flash]]}} "✕"]]]))


(defn- list-tabs
  "Tap a tab to switch lists; long-press (touch) or right-click (mouse) one
  for its rename/delete menu, + adds one."
  [doc current]
  [:div.mb-3
   [:div.tabs.tabs-box.flex-nowrap.overflow-x-auto {:role "tablist"}
    (for [[id l] (model/lists doc)
          :let [menu {:mode :menu :id id}]]
      [:button.tab.list-tab.whitespace-nowrap
       {:replicant/key id
        :role "tab"
        :class (when (= id current) "tab-active")
        :on {:click         (dispatch [:ui/select-list {:id id}])
             :pointerdown   [[:action/long-press-start menu]]
             :pointerup     [[:action/long-press-cancel]]
             :pointerleave  [[:action/long-press-cancel]]
             :pointercancel [[:action/long-press-cancel]]
             :contextmenu   [[:action/context-menu menu]]}}
       (model/fval l :name)])
    [:button.tab {:role "tab" :title "New list" :aria-label "New list"
                  :on {:click [[:action/open-list-dialog {:mode :new}]]}}
     "+"]]])


(defn- items-label
  [n]
  (str n (if (= 1 n) " item" " items")))


(defn- list-menu
  "Rename / Delete for one list. Deleting a list that still has items goes
  through :confirm-delete first; the last list can't be deleted."
  [doc id]
  (let [n          (count (model/items doc id))
        deletable? (< 1 (count (model/lists doc)))]
    (list
      [:h3.font-bold.text-lg.mb-4.break-words (model/list-name doc id)]
      [:div.flex.flex-col.gap-2
       [:button.btn.btn-block
        {:on {:click [[:action/open-list-dialog {:mode :edit :id id}]]}} "Rename"]
       [:button.btn.btn-block.btn-error.btn-outline
        {:disabled (not deletable?)
         :on {:click (if (pos? n)
                       [[:action/open-list-dialog {:mode :confirm-delete :id id}]]
                       [[:action/delete-list id]])}}
        "Delete"]
       (when-not deletable?
         [:p.text-sm.opacity-60 "The last list can't be deleted."])]
      [:div.modal-action
       [:button.btn {:on {:click [[:action/close-list-dialog]]}} "Cancel"]])))


(defn- confirm-delete
  [doc id]
  (let [n (count (model/items doc id))]
    (list
      [:h3.font-bold.text-lg.mb-4 "Delete list?"]
      [:div.alert.alert-warning
       [:span "\"" (model/list-name doc id) "\" still has "
        [:strong (items-label n)] ". Deleting the list deletes "
        (if (= 1 n) "it" "them") " too."]]
      [:div.modal-action
       [:button.btn {:on {:click [[:action/close-list-dialog]]}} "Cancel"]
       [:button.btn.btn-error
        {:on {:click [[:action/delete-list id]]}}
        (str "Delete list and " (items-label n))]])))


(defn- list-form
  "New-list / rename-list form."
  [doc {:keys [mode id]}]
  (list
    [:h3.font-bold.text-lg.mb-4 (if (= mode :edit) "Rename list" "New list")]
    [:form.flex.flex-col.gap-2
     {:on {:submit [[:action/save-list :event/target]]}}
     [:input.input.input-bordered.w-full
      {:replicant/key [mode id]
       :name "name" :required true :autofocus true :autocomplete "off"
       :placeholder "List name"
       :value (if (= mode :edit) (model/list-name doc id) "")}]
     [:div.modal-action
      [:button.btn {:type "button" :on {:click [[:action/close-list-dialog]]}} "Cancel"]
      [:button.btn.btn-primary {:type "submit"} "Save"]]]))


(defn- list-dialog
  [doc {:keys [mode id] :as dialog}]
  [:dialog#list-dialog.modal
   {:on {:close [[:action/close-list-dialog]]}}
   [:div.modal-box
    (case mode
      :menu           (list-menu doc id)
      :confirm-delete (confirm-delete doc id)
      (:new :edit)    (list-form doc dialog)
      nil)]])


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
  [{:keys [doc flash sync-state sync-error remote-config editing-id current-list]
    dialog :list-dialog :as db}]
  (let [current (model/resolve-list doc current-list)
        items   (model/items doc current)]
    [:div.max-w-xl.mx-auto.p-4
     [:div.navbar.bg-base-200.rounded-box.mb-4
      [:div.flex-1 [:h1.text-xl.font-bold.px-2 "koofma"]]
      [:div.flex-none.gap-2.px-2
       (sync-indicator sync-state)
       [:button.btn.btn-ghost.btn-circle.btn-sm
        {:on {:click [[:action/open-settings]]}} "⚙"]]]
     (list-tabs doc current)
     (quick-add)
     [:ul#items.flex.flex-col.gap-1
      (for [[id :as entry] items]
        (item-row entry (= editing-id id) db))]
     (when (empty? items)
       [:p.text-center.opacity-60.mt-8 "Nothing to buy."])
     (flash-toast flash)
     (list-dialog doc dialog)
     (settings-dialog remote-config sync-state sync-error)]))
