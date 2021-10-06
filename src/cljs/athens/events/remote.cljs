(ns athens.events.remote
  "`re-frame` events related to `:remote/*`."
  (:require
    [athens.common-events                 :as common-events]
    [athens.common-events.graph.atomic    :as atomic-graph-ops]
    [athens.common-events.resolver        :as resolver]
    [athens.common-events.resolver.atomic :as atomic-resolver]
    [athens.common-events.schema          :as schema]
    [athens.common.logging                :as log]
    [athens.db                            :as db]
    [athens.events.selection              :as select-events]
    [athens.util                          :as util]
    [malli.core                           :as m]
    [malli.error                          :as me]
    [re-frame.core                        :as rf]))


;; Connection Management

(rf/reg-event-fx
  :remote/connect!
  (fn [_ [_ remote-db]]
    (log/info ":remote/connect!" (pr-str remote-db))
    {:remote/client-connect! remote-db
     :fx                     [[:dispatch [:loading/set]]
                              [:dispatch [:conn-status :connecting]]]}))


(rf/reg-event-fx
  :remote/connected
  (fn [_ _]
    (log/info ":remote/connected")
    {:fx [[:dispatch-n [[:loading/unset]
                        [:conn-status :connected]
                        [:db/sync]]]]}))


(rf/reg-event-fx
  :remote/connection-failed
  (fn [_ _]
    (log/warn ":remote/connection-failed")
    {:fx [[:dispatch-n [[:loading/unset]
                        [:conn-status :disconnected]
                        [:db/sync]]]]}))


(rf/reg-event-fx
  :remote/disconnect!
  (fn [_ _]
    {:remote/client-disconnect! nil}))


;; Remote protocol management (awaiting txs & events, accepting/rejecting events)

(rf/reg-event-db
  :remote/await-event
  (fn [db [_ event]]
    (log/debug "event-id" (pr-str (:event/id event)) "await event")
    (update db :remote/awaited-events (fnil conj #{}) event)))


(rf/reg-event-db
  :remote/await-tx
  (fn [db [_ awaited-tx-id]]
    (log/info "await tx" awaited-tx-id)
    (update db :remote/awaited-tx (fnil conj #{}) awaited-tx-id)))


(rf/reg-event-fx
  :remote/accepted-event
  (fn [{db :db} [_ {:keys [event-id]}]]
    (let [followups (get-in db [:remote/followup event-id])]
      (log/debug "event-id:" (pr-str event-id)
                 ":remote/accepted-event: followup count:" (count followups))
      ;; TODO(rtc): automatically remove/unregister followup
      (when (seq followups)
        {:fx followups}))))


(rf/reg-event-fx
  :remote/accept-event
  (fn [{db :db} [_ {:keys [event-id tx-id]}]]
    (log/info "event-id:" (pr-str event-id) "accept event")
    (let [awaited-event   (->> (:remote/awaited-events db)
                               (filter #(= event-id (:event/id %)))
                               first)
          acceptance-info {:event-id event-id
                           :tx-id    tx-id
                           :event    awaited-event}
          last-seen-tx    (:remote/last-seen-tx db -1)
          events          (cond-> []
                            (< last-seen-tx tx-id)
                            (conj [:remote/await-tx tx-id])
                            true
                            (conj [:remote/accepted-event acceptance-info]))]
      (log/debug "event-id:" (pr-str event-id)
                 "re-frame events to dispatch:" (pr-str events))
      {:db (-> db
               (update :remote/awaited-events disj awaited-event)
               (update :remote/accepted-events (fnil conj #{}) acceptance-info))
       :fx [[:dispatch-n events]]})))


(rf/reg-event-db
  :remote/reject-event
  (fn [db [_ {:keys [event-id reason data]}]]
    (log/info "event-id:" (pr-str event-id) "rejected event")
    (let [awaited-event  (->> (:remote/awaited-events db)
                              (filter #(= event-id (:event/id %)))
                              first)
          rejection-info {:event-id  event-id
                          :rejection {:reason reason
                                      :data   data}
                          :event     awaited-event}]
      (-> db
          (update :remote/awaited-events disj awaited-event)
          (update :remote/rejected-events (fnil conj #{}) rejection-info)))))


(rf/reg-event-db
  :remote/fail-event
  (fn [db [_ {:keys [event-id reason] :as failure-event}]]
    (log/warn "event-id:" (pr-str event-id)
              "failed event:" (pr-str failure-event))
    (let [awaited-event (->> (:remote/awaited-events db)
                             (filter #(= event-id (:event/id %)))
                             first)
          failure-info  {:event-id event-id
                         :reason   reason
                         :event    awaited-event}]
      (-> db
          (update :remote/awaited-events disj awaited-event)
          (update :remote/failed-events (fnil conj #{}) failure-info)))))


(rf/reg-event-db
  :remote/updated-last-seen-tx
  (fn [db _]
    ;; TODO(RTC): clean this up, we don't need last seen TX no mo
    (log/debug ":remote/updated-last-seen-tx")
    db))


(rf/reg-event-fx
  :remote/last-seen-tx!
  (fn [{db :db} [_ new-tx-id]]
    (log/debug "last-seen-tx!" new-tx-id)
    {:db (assoc db :remote/last-seen-tx new-tx-id)
     :fx [[:dispatch [:remote/updated-last-seen-tx]]]}))


;; `re-frame` followup events

(defn- get-event-acceptance-info
  [db event-id]
  (->> db
       :remote/accepted-events
       (filter #(= event-id (:event-id %)))
       first))


(defn- followup-fx
  [db event-id fx]
  (update db :remote/followup (fnil assoc {}) event-id fx))


(rf/reg-event-db
  :remote/register-followup
  (fn [db [_ event-id fx]]
    (followup-fx db event-id fx)))


(rf/reg-event-db
  :remote/unregister-followup
  (fn [db [_ event-id]]
    (update db :remote/followup dissoc event-id)))


;; Send it

(rf/reg-event-fx
  :remote/send-event!
  (fn [_ [_ event]]
    (if (schema/valid-event? event)
      ;; valid event, send item
      {:fx                    [[:dispatch [:remote/await-event event]]]
       :remote/send-event-fx! event}
      (let [explanation (-> schema/event
                            (m/explain event)
                            (me/humanize))]
        ;; TODO display alert?
        (log/warn "Not sending invalid event. Error:" (pr-str explanation)
                  "\nInvalid event was:" (pr-str event))))))


;; Remote graph related events

(rf/reg-event-fx
  :remote/apply-forwarded-event
  (fn [{_db :db} [_ event]]
    (let [{:event/keys [id type]} event
          txs                     (if (contains? #{:op/atomic} type)
                                    (atomic-resolver/resolve-atomic-op-to-tx @db/dsdb (:event/op event))
                                    (resolver/resolve-event-to-tx @db/dsdb event))]
      (log/debug "event-id:" (pr-str id)
                 ", type:" (pr-str type)
                 ":remote/apply-forwarded-event resolved txs:" (pr-str txs))
      {:fx [[:dispatch [:transact txs]]]})))


;; - Page related


(rf/reg-event-fx
  :remote/followup-page-create
  (fn [{db :db} [_ event-id shift?]]
    (let [{:keys [event]}     (get-event-acceptance-info db event-id)
          {:keys [page-uid
                  block-uid]} (:event/args event)]
      (log/debug ":remote/followup-page-create, page-uid" page-uid)
      {:fx [[:dispatch-n [(cond
                            shift?
                            [:right-sidebar/open-item page-uid]

                            (not (util/is-daily-note page-uid))
                            [:navigate :page {:id page-uid}]

                            (util/is-daily-note page-uid)
                            [:daily-note/add page-uid])

                          [:editing/uid block-uid]
                          [:remote/unregister-followup event-id]]]]})))


(rf/reg-event-fx
  :remote/page-create
  (fn [{db :db} [_ page-uid  block-uid title shift?]]
    (let [last-seen-tx                 (:remote/last-seen-tx db)
          {event-id :event/id
           :as      page-create-event} (common-events/build-page-create-event last-seen-tx
                                                                              page-uid
                                                                              block-uid
                                                                              title)
          followup-fx                  [[:dispatch [:remote/followup-page-create event-id shift?]]]]
      (log/debug ":remote/page-create" (pr-str page-create-event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! page-create-event]]]]})))


(rf/reg-event-fx
  :remote/followup-page-rename
  (fn [{_db :db} [_ event-id callback]]
    (log/debug ":remote/followup-page-rename" event-id)
    {:fx [[:invoke-callback callback]]}))


(rf/reg-event-fx
  :remote/page-rename
  (fn [{db :db} [_ uid old-name new-name callback]]
    (let [last-seen-tx                 (:remote/last-seen-tx db)
          {event-id :event/id
           :as      page-rename-event} (common-events/build-page-rename-event last-seen-tx
                                                                              uid
                                                                              old-name
                                                                              new-name)
          followup-fx                  [[:dispatch [:remote/followup-page-rename event-id callback]]]]
      (log/debug ":remote/page-rename" (pr-str page-rename-event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! page-rename-event]]]]})))


(rf/reg-event-fx
  :remote/followup-page-merge
  (fn [{_db :db} [_ event-id callback]]
    (log/debug ":remote/followup-page-merge" event-id)
    {:fx [[:invoke-callback callback]]}))


(rf/reg-event-fx
  :remote/page-merge
  (fn [{db :db} [_ uid old-name new-name callback]]
    (let [last-seen-tx                (:remote/last-seen-tx db)
          {event-id :event/id
           :as      page-merge-event} (common-events/build-page-merge-event last-seen-tx
                                                                            uid
                                                                            old-name
                                                                            new-name)
          followup-fx                 [[:dispatch [:remote/followup-page-merge event-id callback]]]]
      (log/debug ":remote/page-merge" (pr-str page-merge-event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! page-merge-event]]]]})))


(rf/reg-event-fx
  :remote/page-delete
  (fn [{db :db} [_ uid]]
    (let [last-seen-tx      (:remote/last-seen-tx db)
          page-delete-event (common-events/build-page-delete-event last-seen-tx
                                                                   uid)]
      (log/debug ":remote/page-delete" (pr-str page-delete-event))
      {:fx [[:dispatch [:remote/send-event! page-delete-event]]]})))


(rf/reg-event-fx
  :remote/page-add-shortcut
  (fn [{db :db} [_ uid]]
    (let [last-seen-tx       (:remote/last-seen-tx db)
          add-shortcut-event (common-events/build-page-add-shortcut last-seen-tx uid)]
      (log/debug ":remote/page-add-shortcut:" (pr-str add-shortcut-event))
      {:fx [[:dispatch [:remote/send-event! add-shortcut-event]]]})))


(rf/reg-event-fx
  :remote/page-remove-shortcut
  (fn [{db :db} [_ uid]]
    (let [last-seen-tx          (:remote/last-seen-tx db)
          remove-shortcut-event (common-events/build-page-remove-shortcut last-seen-tx uid)]
      (log/debug ":remote/page-remove-shortcut:" (pr-str remove-shortcut-event))
      {:fx [[:dispatch [:remote/send-event! remove-shortcut-event]]]})))


(rf/reg-event-fx
  :remote/left-sidebar-drop-above
  (fn [{db :db} [_ source-order target-order]]
    (let [last-seen-tx                  (:remote/last-seen-tx db)
          left-sidebar-drop-above-event (common-events/build-left-sidebar-drop-above last-seen-tx source-order target-order)]
      (log/debug ":remote/left-sidebar-drop-above" (pr-str left-sidebar-drop-above-event))
      {:fx [[:dispatch [:remote/send-event! left-sidebar-drop-above-event]]]})))


(rf/reg-event-fx
  :remote/left-sidebar-drop-below
  (fn [{db :db} [_ source-order target-order]]
    (let [last-seen-tx                  (:remote/last-seen-tx db)
          left-sidebar-drop-below-event (common-events/build-left-sidebar-drop-below last-seen-tx source-order target-order)]
      (log/debug ":remote/left-sidebar-drop-below" (pr-str left-sidebar-drop-below-event))
      {:fx [[:dispatch [:remote/send-event! left-sidebar-drop-below-event]]]})))


(rf/reg-event-fx
  :remote/unlinked-references-link
  (fn [{db :db} [_ string uid title]]
    (let [last-seen-tx                   (:remote/last-seen-tx db)
          unlinked-references-link-event (common-events/build-unlinked-references-link last-seen-tx uid string title)]
      (log/debug ":remote/unlinked-references-link:" (pr-str unlinked-references-link-event))
      {:fx [[:dispatch [:remote/send-event! unlinked-references-link-event]]]})))


(rf/reg-event-fx
  :remote/unlinked-references-link-all
  (fn [{db :db} [_ unlinked-refs title]]
    (let [last-seen-tx                       (:remote/last-seen-tx db)
          unlinked-references-link-all-event (common-events/build-unlinked-references-link-all last-seen-tx unlinked-refs title)]
      (log/debug ":remote/unlinked-references-link-all:" (pr-str unlinked-references-link-all-event))
      {:fx [[:dispatch [:remote/send-event! unlinked-references-link-all-event]]]})))


;; - Block related

(rf/reg-event-fx
  :remote/followup-block-save
  (fn [{_db :db} [_ {:keys [event-id callback]}]]
    (log/debug ":remote/followup-block-save" event-id)
    {:fx [[:invoke-callback callback]
          [:dispatch [:remote/unregister-followup event-id]]]}))


(rf/reg-event-fx
  :remote/block-save
  (fn [{db :db} [_ {:keys [op callback]}]]
    (let [last-seen-tx     (:remote/last-seen-tx db)
          {event-id :event/id
           :as      event} (common-events/build-atomic-event last-seen-tx op)
          followup-fx      [[:dispatch [:remote/followup-block-save {:event-id event-id
                                                                     :callback callback}]]]]
      (log/debug ":remote/block-save" (pr-str event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! event]]]]})))


(rf/reg-event-fx
  :remote/followup-new-block
  (fn [_ [_ {:keys [event-id embed-id new-uid]}]]
    (log/debug ":remote/followup-new-block" event-id)
    (log/info ":remote/followup-new-block, new-uid" new-uid)
    {:fx [[:dispatch-n [[:editing/uid (str new-uid (when embed-id
                                                     (str "-embed-" embed-id)))]
                        [:remote/unregister-followup event-id]]]]}))


(rf/reg-event-fx
  :remote/new-block
  (fn [{db :db} [_ {:keys [block parent new-uid embed-id]}]]
    (let [last-seen-tx               (:remote/last-seen-tx db)
          block-new-op               (atomic-graph-ops/make-block-new-op (:block/uid parent)
                                                                         new-uid
                                                                         (:block/order block))
          {event-id :event/id
           :as      block-new-event} (common-events/build-atomic-event last-seen-tx
                                                                       block-new-op)
          followup-fx                [[:dispatch [:remote/followup-new-block {:event-id event-id
                                                                              :embed-id embed-id
                                                                              :new-uid  new-uid}]]]]
      (log/debug ":remote/new-block" (pr-str block-new-event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! block-new-event]]]]})))


(rf/reg-event-fx
  :remote/followup-page-new
  (fn [_ [_ event-id page-uid block-uid]]
    (log/debug ":remote/followup-page-new" event-id)
    (log/info ":remote/followup-page-new, page-uid" page-uid)
    {:fx [[:dispatch-n [[:editing/uid block-uid]
                        [:remote/unregister-followup event-id]]]]}))


(rf/reg-event-fx
  :remote/page-new
  (fn [{db :db} [_ page-uid block-uid title]]
    (let [last-seen-tx  (:remote/last-seen-tx db)
          page-new-op   (atomic-graph-ops/make-page-new-op title
                                                           page-uid
                                                           block-uid)
          {event-id :event/id
           :as      page-new-event} (common-events/build-atomic-event last-seen-tx
                                                                      page-new-op)

          followup-fx                  [[:dispatch [:remote/followup-page-new event-id page-uid block-uid]]]]
      (log/debug ":remote/page-new" (pr-str page-new-event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! page-new-event]]]]})))


(rf/reg-event-fx
  :remote/delete-only-child
  (fn [{db :db} [_ uid]]
    (let [last-seen-tx          (:remote/last-seen-tx db)
          {event-id :event/id
           :as delete-only-child-event} (common-events/build-delete-only-child-event last-seen-tx uid)
          followup-fx                [[:dispatch [:editing/uid nil]]]]
      (log/debug ":remote/delete-only-child" (pr-str delete-only-child-event))
      {:fx [[:dispatch [:remote/register-followup event-id followup-fx]]
            [:dispatch [:remote/send-event! delete-only-child-event]]]})))


(rf/reg-event-fx
  :remote/followup-delete-merge-block
  (fn [_ [_ {:keys [event-id prev-block-uid embed-id prev-block]}]]
    (log/debug ":remote/followup-delete-merge-block" event-id)
    {:fx [[:dispatch [:editing/uid
                      (cond-> prev-block-uid
                        embed-id (str "-embed-" embed-id))
                      (count (:block/string prev-block))]]
          [:dispatch [:remote/unregister-followup event-id]]]}))


(rf/reg-event-fx
  :remote/delete-merge-block
  (fn [{db :db} [_ {:keys [uid value prev-block-uid embed-id prev-block]}]]
    (let [last-seen-tx          (:remote/last-seen-tx db)
          {event-id :event/id
           :as delete-merge-block-event} (common-events/build-delete-merge-block-event last-seen-tx uid value)
          followup-fx                    [[:dispatch [:remote/followup-delete-merge-block {:event-id event-id
                                                                                           :prev-block-uid prev-block-uid
                                                                                           :prev-block prev-block
                                                                                           :embed-id embed-id}]]]]
      (log/debug ":remote/delete-merge-block:" (pr-str delete-merge-block-event))
      {:fx [[:dispatch [:remote/register-followup event-id followup-fx]]
            [:dispatch [:remote/send-event! delete-merge-block-event]]]})))


(rf/reg-event-fx
  :remote/followup-add-child
  (fn [{db :db} [_ {:keys [event-id embed-id]}]]
    (log/debug ":remote/followup-add-child" event-id)
    (let [{:keys [event]} (get-event-acceptance-info db event-id)
          {:keys [new-uid]} (:event/args event)]
      (log/info ":remote/followup-add-child, new-uid" new-uid)
      {:fx [[:dispatch-n [[:editing/uid (str new-uid (when embed-id
                                                       (str "-embed-" embed-id)))]
                          [:remote/unregister-followup event-id]]]]})))


(rf/reg-event-fx
  :remote/add-child
  (fn [{db :db} [_ {:keys [parent-uid new-uid embed-id add-time?]}]]
    (let [last-seen-tx               (:remote/last-seen-tx db)
          {event-id :event/id
           :as      add-child-event} (common-events/build-add-child-event last-seen-tx
                                                                          parent-uid
                                                                          new-uid
                                                                          add-time?)
          followup-fx                [[:dispatch [:remote/followup-add-child {:event-id event-id
                                                                              :embed-id embed-id}]]]]
      (log/debug ":remote/add-child" (pr-str add-child-event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! add-child-event]]]]})))


(rf/reg-event-fx
  :remote/followup-open-block-add-child
  (fn [{db :db} [_ {:keys [event-id embed-id] :as args}]]
    (log/debug ":remote/followup-open-block-add-child" (pr-str args))
    (let [{:keys [event]}   (get-event-acceptance-info db event-id)
          {:keys [new-uid]} (:event/args event)]
      (log/info ":remote/followup-open-block-add-child, new-uid" new-uid
                      ", embed-id" embed-id)
      {:fx [[:dispatch [:editing/uid (str new-uid (when embed-id
                                                    (str "-embed-" embed-id)))]]]})))


(rf/reg-event-fx
  :remote/open-block-add-child
  (fn [{db :db} [_ {:keys [parent-uid new-uid embed-id]}]]
    (let [last-seen-tx               (:remote/last-seen-tx db)
          {event-id :event/id
           :as      open-block-add-child-event} (common-events/build-open-block-add-child-event last-seen-tx
                                                                                                parent-uid
                                                                                                new-uid)
          followup-fx                [[:dispatch [:remote/followup-open-block-add-child {:event-id event-id
                                                                                         :embed-id embed-id}]]]]
      (log/debug ":remote/open-block-add-child" (pr-str open-block-add-child-event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! open-block-add-child-event]]]]})))


(rf/reg-event-fx
  :remote/followup-split-block
  (fn [_ [_ {:keys [embed-id new-uid]}]]
    (log/debug ":remote/followup-split-block new-uid:" new-uid
                      ", embed-id:" embed-id)
    {:fx [[:dispatch [:editing/uid (str new-uid (when embed-id
                                                  (str "-embed-" embed-id)))]]]}))


(rf/reg-event-fx
  :remote/split-block
  (fn [{db :db} [_ {:keys [op new-uid embed-id] :as args}]]
    (log/debug ":remote/split-block args" (pr-str args))
    (let [last-seen-tx                 (:remote/last-seen-tx db)
          {event-id :event/id
           :as      split-block-event} (common-events/build-atomic-event last-seen-tx op)
          followup-fx                  [[:dispatch [:remote/followup-split-block {:embed-id embed-id
                                                                                  :new-uid  new-uid}]]]]
      (log/debug ":remote/split-block event" (pr-str split-block-event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! split-block-event]]]]})))


(rf/reg-event-fx
  :remote/followup-split-block-to-children
  (fn [{db :db} [_ {:keys [event-id embed-id] :as args}]]
    (log/debug ":remote/followup-split-block-to-children args" (pr-str args))
    (let [{:keys [event]}   (get-event-acceptance-info db event-id)
          {:keys [new-uid]} (:event/args event)]
      (log/debug ":remote/followup-split-block-to-children new-uid:" new-uid
                        ", embed-id" embed-id)
      {:fx [[:dispatch [:editing/uid (str new-uid (when embed-id
                                                    (str "-embed-" embed-id)))]]]})))


(rf/reg-event-fx
  :remote/split-block-to-children
  (fn [{db :db} [_ {:keys [uid value index new-uid embed-id] :as args}]]
    (log/debug ":remote/split-block-to-children args" (pr-str args))
    (let [last-seen-tx     (:remote/last-seen-tx db)
          {event-id :event/id
           :as      event} (common-events/build-split-block-to-children-event last-seen-tx
                                                                              uid
                                                                              value
                                                                              index
                                                                              new-uid)
          followup-fx      [[:dispatch [:remote/followup-split-block-to-children {:event-id event-id
                                                                                  :embed-id embed-id}]]]]
      (log/debug ":remote/split-block-to-children event" (pr-str event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! event]]]]})))


(rf/reg-event-fx
  :remote/followup-indent
  (fn [{db :db} [_ {:keys [event-id start end] :as args}]]
    (log/debug ":remote/followup-indent args" (pr-str args))
    (let [{:keys [event]} (get-event-acceptance-info db event-id)
          {:keys [uid]}   (:event/args event)]
      (log/debug ":remote/followup-indent uid:" uid)
      {:fx [[:set-cursor-position [uid start end]]]})))


(rf/reg-event-fx
  :remote/indent
  (fn [{db :db} [_ {:keys [uid value start end] :as args}]]
    (log/debug ":remote/indent args" (pr-str args))
    (let [last-seen-tx        (:remote/last-seen-tx db)
          {event-id :event/id
           :as      event}    (common-events/build-indent-event last-seen-tx uid value)
          followup-fx         [[:dispatch [:remote/followup-indent {:event-id event-id
                                                                    :start    start
                                                                    :end      end}]]]]
      (log/debug ":remote/indent event" (pr-str event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! event]]]]})))


(rf/reg-event-fx
  :remote/indent-multi
  (fn [{db :db} [_ {:keys [uids] :as args}]]
    (log/debug ":remote/indent-multi args" args)
    (let [last-seen-tx        (:remote/last-seen-tx db)
          indent-multi-event  (common-events/build-indent-multi-event last-seen-tx uids)]
      (log/debug ":remote/indent-multi event" (pr-str indent-multi-event))
      {:fx [[:dispatch [:remote/send-event! indent-multi-event]]]})))


(rf/reg-event-fx
  :remote/followup-unindent
  (fn [{db :db} [_ {:keys [event-id embed-id start end] :as args}]]
    (log/debug ":remote/followup-unindent args" (pr-str args))
    (let [{:keys [event]} (get-event-acceptance-info db event-id)
          {:keys [uid]}   (:event/args event)]
      (log/debug ":remote/followup-unindent uid:" uid
                        ", embed-id:" embed-id)
      {:fx [[:dispatch [:editing/uid (str uid (when embed-id
                                                (str "-embed-" embed-id)))]]
            [:set-cursor-position [uid start end]]]})))


(rf/reg-event-fx
  :remote/unindent
  (fn [{db :db} [_ {:keys [uid value start end embed-id] :as args}]]
    (log/debug ":remote/unindent args" (pr-str args))
    (let [last-seen-tx     (:remote/last-seen-tx db)
          {event-id :event/id
           :as      event} (common-events/build-unindent-event last-seen-tx uid value)
          followup-fx      [[:dispatch [:remote/followup-unindent {:event-id event-id
                                                                   :embed-id embed-id
                                                                   :start    start
                                                                   :end      end}]]]]
      (log/debug ":remote/unindent event" (pr-str event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! event]]]]})))


(rf/reg-event-fx
  :remote/unindent-multi
  (fn [{db :db} [_ {:keys [uids] :as args}]]
    (log/debug ":remote/unindent-multi args" args)
    (let [last-seen-tx           (:remote/last-seen-tx db)
          unindent-multi-event   (common-events/build-unindent-multi-event last-seen-tx uids)]
      (log/debug ":remote/unindent-multi event" (pr-str unindent-multi-event))
      {:fx [[:dispatch [:remote/send-event! unindent-multi-event]]]})))


(rf/reg-event-fx
  :remote/followup-bump-up
  (fn [{db :db} [_ {:keys [event-id embed-id] :as args}]]
    (log/debug ":remote/followup-bump-up args" (pr-str args))
    (let [{:keys [event]}   (get-event-acceptance-info db event-id)
          {:keys [new-uid]} (:event/args event)]
      (log/debug ":remote/followup-bump-up new-uid:" new-uid
                        ", embed-id:" embed-id)
      {:fx [[:dispatch [:editing/uid (str new-uid (when embed-id
                                                    (str "-embed-" embed-id)))]]]})))


(rf/reg-event-fx
  :remote/bump-up
  (fn [{db :db} [_ {:keys [uid new-uid embed-id] :as args}]]
    (log/debug ":remote/bump-up args" (pr-str args))
    (let [last-seen-tx     (:remote/last-seen-tx db)
          {event-id :event/id
           :as      event} (common-events/build-bump-up-event last-seen-tx
                                                              uid
                                                              new-uid)
          followup-fx [[:dispatch [:remote/followup-bump-up {:event-id event-id
                                                             :embed-id embed-id}]]]]
      (log/debug ":remote/bump-up event" (pr-str event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event! event]]]]})))


(rf/reg-event-fx
  :remote/paste-verbatim
  (fn [{db :db} [_ uid text start value]]
    (let [last-seen-tx         (:remote/last-seen-tx db)
          paste-verbatim-event (common-events/build-paste-verbatim-event last-seen-tx
                                                                         uid
                                                                         text
                                                                         start
                                                                         value)]
      {:fx [[:dispatch [:remote/send-event! paste-verbatim-event]]]})))


(rf/reg-event-fx
  :remote/drop-child
  (fn [{db :db} [_ {:keys [source-uid target-uid] :as args}]]
    (log/debug ":remote/drop-child args" (pr-str args))
    (let [last-seen-tx     (:remote/last-seen-tx db)
          drop-child-event (common-events/build-drop-child-event last-seen-tx
                                                                 source-uid
                                                                 target-uid)]
      (log/debug ":remote/drop-child event" drop-child-event)
      {:fx [[:dispatch [:remote/send-event! drop-child-event]]]})))


(rf/reg-event-fx
  :remote/drop-multi-child
  (fn [{db :db} [_ {:keys [source-uids target-uid] :as args}]]
    (log/debug ":remote/drop-multi-child args" (pr-str args))
    (let [last-seen-tx           (:remote/last-seen-tx db)
          drop-multi-child-event (common-events/build-drop-multi-child-event last-seen-tx
                                                                             source-uids
                                                                             target-uid)]
      (log/debug ":remote/drop-multi-child event" drop-multi-child-event)
      {:fx [[:dispatch [:remote/send-event! drop-multi-child-event]]]})))


(rf/reg-event-fx
  :remote/drop-link-child
  (fn [{db :db} [_ {:keys [source-uid target-uid] :as args}]]
    (log/debug ":remote/drop-link-child args" (pr-str args))
    (let [last-seen-tx          (:remote/last-seen-tx db)
          drop-link-child-event (common-events/build-drop-link-child-event last-seen-tx
                                                                           source-uid
                                                                           target-uid)]
      (log/debug ":remote/drop-link-child event" drop-link-child-event)
      {:fx [[:dispatch [:remote/send-event! drop-link-child-event]]]})))


(rf/reg-event-fx
  :remote/drop-diff-parent
  (fn [{db :db} [_ {:keys [drag-target source-uid target-uid] :as args}]]
    (log/debug ":remote/drop-diff-parent args" (pr-str args))
    (let [last-seen-tx     (:remote/last-seen-tx db)
          drop-diff-parent-event  (common-events/build-drop-diff-parent-event last-seen-tx
                                                                              drag-target
                                                                              source-uid
                                                                              target-uid)]
      (log/debug ":remote/drop-diff-parent event" drop-diff-parent-event)
      {:fx [[:dispatch [:remote/send-event! drop-diff-parent-event]]]})))


(rf/reg-event-fx
  :remote/drop-diff-source-same-parents
  (fn [{db :db} [_ {:keys [drag-target source-uids target-uid] :as args}]]
    (log/debug ":remote/drop-diff-source-same-parents args" (pr-str args))
    (let [last-seen-tx     (:remote/last-seen-tx db)
          drop-diff-source-same-parents-event  (common-events/build-drop-multi-diff-source-same-parents-event last-seen-tx
                                                                                                              drag-target
                                                                                                              source-uids
                                                                                                              target-uid)]
      (log/debug ":remote/drop-diff-source-same-parents event" drop-diff-source-same-parents-event)
      {:fx [[:dispatch [:remote/send-event! drop-diff-source-same-parents-event]]]})))


(rf/reg-event-fx
  :remote/drop-diff-source-diff-parents
  (fn [{db :db} [_ {:keys [drag-target source-uids target-uid] :as args}]]
    (log/debug ":remote/drop-diff-source-diff-parents args" (pr-str args))
    (let [last-seen-tx     (:remote/last-seen-tx db)
          drop-diff-source-diff-parents-event  (common-events/build-drop-multi-diff-source-diff-parents-event last-seen-tx
                                                                                                              drag-target
                                                                                                              source-uids
                                                                                                              target-uid)]
      (log/debug ":remote/drop-diff-source-diff-parents event" drop-diff-source-diff-parents-event)
      {:fx [[:dispatch [:remote/send-event! drop-diff-source-diff-parents-event]]]})))


(rf/reg-event-fx
  :remote/drop-link-diff-parent
  (fn [{db :db} [_ {:keys [drag-target source-uid target-uid] :as args}]]
    (log/debug ":remote/drop-link-diff-parent args" (pr-str args))
    (let [last-seen-tx     (:remote/last-seen-tx db)
          drop-link-diff-parent-event  (common-events/build-drop-link-diff-parent-event last-seen-tx
                                                                                        drag-target
                                                                                        source-uid
                                                                                        target-uid)]
      (log/debug ":remote/drop-link-diff-parent event" drop-link-diff-parent-event)
      {:fx [[:dispatch [:remote/send-event! drop-link-diff-parent-event]]]})))


(rf/reg-event-fx
  :remote/drop-same
  (fn [{db :db} [_ {:keys [drag-target source-uid target-uid] :as args}]]
    (log/debug "remote/drop-same args" (pr-str args))
    (let [last-seen-tx (:remote/last-seen-tx db)
          drop-same-event (common-events/build-drop-same-event last-seen-tx
                                                               drag-target
                                                               source-uid
                                                               target-uid)]
      (log/debug ":remote/drop-same event" drop-same-event)
      {:fx [[:dispatch [:remote/send-event! drop-same-event]]]})))


(rf/reg-event-fx
  :remote/drop-multi-same-source
  (fn [{db :db} [_ {:keys [drag-target source-uids target-uid] :as args}]]
    (log/debug "remote/drop-multi-same-source args" (pr-str args))
    (let [last-seen-tx (:remote/last-seen-tx db)
          drop-multi-same-source-event (common-events/build-drop-multi-same-source-event last-seen-tx
                                                                                         drag-target
                                                                                         source-uids
                                                                                         target-uid)]
      (log/debug ":remote/drop--same- event" drop-multi-same-source-event)
      {:fx [[:dispatch [:remote/send-event! drop-multi-same-source-event]]]})))


(rf/reg-event-fx
  :remote/drop-multi-same-all
  (fn [{db :db} [_ {:keys [drag-target source-uids target-uid] :as args}]]
    (log/debug "remote/drop-multi-same-all args" (pr-str args))
    (let [last-seen-tx (:remote/last-seen-tx db)
          drop-multi-same-all-event (common-events/build-drop-multi-same-all-event last-seen-tx
                                                                                   drag-target
                                                                                   source-uids
                                                                                   target-uid)]
      (log/debug ":remote/drop-multi-same-all event" drop-multi-same-all-event)
      {:fx [[:dispatch [:remote/send-event! drop-multi-same-all-event]]]})))


(rf/reg-event-fx
  :remote/drop-link-same-parent
  (fn [{db :db} [_ {:keys [drag-target source-uid target-uid] :as args}]]
    (log/debug "remote/drop-link-same-parent args" (pr-str args))
    (let [last-seen-tx (:remote/last-seen-tx db)
          drop-link-same-parent-event (common-events/build-drop-link-same-parent-event last-seen-tx
                                                                                       drag-target
                                                                                       source-uid
                                                                                       target-uid)]
      (log/debug ":remote/drop-link-same-parent event" drop-link-same-parent-event)
      {:fx [[:dispatch [:remote/send-event! drop-link-same-parent-event]]]})))


(rf/reg-event-fx
  :remote/followup-selected-delete
  (fn [{_db :db} [_ event-id]]
    (log/debug ":remote/followup-selected-delete" event-id)
    {:fx [[:dispatch-n [[:editing/uid nil]
                        [:remote/unregister-followup event-id]
                        [::select-events/clear]]]]}))


(rf/reg-event-fx
  :remote/selected-delete
  (fn [{db :db} [_ uids]]
    (let [last-seen-tx                 (:remote/last-seen-tx db)
          {event-id :event/id
           :as      selected-delete-event} (common-events/build-selected-delete-event last-seen-tx
                                                                                      uids)
          followup-fx                  [[:dispatch [:remote/followup-selected-delete event-id]]]]
      (log/debug ":remote/selected-delete" (pr-str selected-delete-event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event!       selected-delete-event]]]]})))


(rf/reg-event-fx
  :remote/block-open
  (fn [{db :db} [_ {:keys [block-uid open?]}]]
    (let [last-seen-tx      (:remote/last-seen-tx db)
          block-open-event  (common-events/build-block-open-event last-seen-tx
                                                                  block-uid
                                                                  open?)]
      (log/debug ":remote/block-open" (pr-str block-open-event))
      {:fx [[:dispatch-n [[:remote/send-event!       block-open-event]]]]})))


;; TODO: we don't have followup for remote paste event, because current implementation relies on analyzing tx
;; this ain't available in current remote events protocol.
(rf/reg-event-fx
  :remote/followup-paste
  (fn [{_db :db} [_ event-id]]
    (log/debug ":remote/followup-paste" event-id)
    {:fx [:dispatch-n [[:editing/uid nil]
                       [:remote/unregister-followup event-id]]]}))


(rf/reg-event-fx
  :remote/paste
  (fn [{db :db} [_ uid text start value]]
    (let [last-seen-tx           (:remote/last-seen-tx db)
          {event-id :event/id
           :as      paste-event} (common-events/build-paste-event last-seen-tx
                                                                  uid
                                                                  text
                                                                  start
                                                                  value)
          followup-fx            [[:dispatch [:remote/followup-paste event-id]]]]
      (log/debug ":remote/[paste" (pr-str paste-event))
      {:fx [[:dispatch-n [[:remote/register-followup event-id followup-fx]
                          [:remote/send-event!       paste-event]]]]})))
