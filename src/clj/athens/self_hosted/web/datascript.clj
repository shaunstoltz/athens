(ns athens.self-hosted.web.datascript
  (:require
    [athens.common-events                 :as common-events]
    [athens.common-events.resolver.atomic :as atomic-resolver]
    [athens.common.logging                :as log]
    [athens.self-hosted.clients           :as clients])
  (:import
    (clojure.lang
      ExceptionInfo)))


(def supported-atomic-ops
  #{:block/new
    :block/save
    :block/open
    :block/remove
    :block/move
    :page/new
    :page/rename
    :page/merge
    :page/remove
    :shortcut/new
    :shortcut/remove
    :shortcut/move
    :composite/consequence})


(def single-writer-guard (Object.))


(defn exec!
  [conn {:event/keys [id] :as event}]
  (locking single-writer-guard
    (try
      (atomic-resolver/resolve-transact! conn event)
      (common-events/build-event-accepted id)
      (catch ExceptionInfo ex
        (let [err-msg   (ex-message ex)
              err-data  (ex-data ex)
              err-cause (ex-cause ex)]
          (log/error ex (str "Exec event-id: " id
                             " FAIL: " (pr-str {:msg   err-msg
                                                :data  err-data
                                                :cause err-cause})))
          (common-events/build-event-rejected id err-msg err-data))))))


(defn atomic-op-handler
  [conn channel {:event/keys [id op] :as event}]
  (let [username          (clients/get-client-username channel)
        {:op/keys [type]} op]
    (log/debug "username:" username
               "event-id:" id
               "-> Received Atomic Op Type:" (pr-str type))
    (if (contains? supported-atomic-ops type)
      (exec! conn event)
      (common-events/build-event-rejected id
                                          (str "Under development event: " type)
                                          {:unsuported-type type}))))
