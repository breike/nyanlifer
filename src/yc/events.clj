(ns yc.events
  "События: локальная история (SQLite) + создание через CalDAV + теги."
  (:require [yc.db :as db]
            [yc.calendars :as cal]
            [yc.tags :as tags]
            [yc.util :as util]))

(defn event-row->map
  [row]
  (when row
    (let [uid (:uid row)]
      (assoc (into {} row)
             :tags (tags/tags-of-event uid)
             :notes (mapv :id (db/query
                                ["SELECT id FROM notes WHERE event_id=? ORDER BY id" uid]))))))

(defn list-events
  ([user-id] (list-events user-id nil nil))
  ([user-id q tag-name]
   (let [sql (str "SELECT * FROM events WHERE user_id=?"
                  (when q " AND (summary LIKE ? OR desc LIKE ?)")
                  (when tag-name " AND uid IN (SELECT event_uid FROM event_tags et JOIN tags t ON t.id=et.tag_id WHERE t.name=?)")
                  " ORDER BY created_at DESC LIMIT 200")
         params (cond-> [user-id]
                  q (conj (str "%" q "%") (str "%" q "%"))
                  tag-name (conj tag-name))]
     (mapv event-row->map (db/query (vec (cons sql params)))))))

(defn get-event
  [user-id uid]
  (event-row->map (first (db/query ["SELECT * FROM events WHERE uid=? AND user_id=?" uid user-id]))))

(defn yandex-credentials
  "Креды Яндекса из env (или nil при отсутствии)."
  []
  (let [cred {:server (or (System/getenv "YANDEX_CALDAV_SERVER") cal/default-server)
              :login (System/getenv "YANDEX_CALDAV_LOGIN")
              :password (System/getenv "YANDEX_CALDAV_PASSWORD")}]
    (when (and (:login cred) (:password cred))
      cred)))

(defn create-event!
  "Создаёт событие через CalDAV и сохраняет в локальную историю + теги."
  [user-id {:keys [summary start duration desc loc calendar_href tags]}]
  (let [cred (yandex-credentials)]
    (when-not cred
      (throw (ex-info "YANDEX_CALDAV_LOGIN/PASSWORD не заданы" {:yc/error :config})))
    (let [{:keys [uid url summary start duration all_day desc loc calendar] :as ev}
          (cal/create-event (:server cred) (:login cred) (:password cred)
                            summary start
                            {:duration_min (or duration 60)
                             :desc (or desc "") :loc (or loc "")
                             :calendar_href calendar_href})]
      (db/execute!
        ["INSERT OR REPLACE INTO events
          (uid, user_id, summary, start, duration, all_day, desc, loc, calendar_href, url, created_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?)"
         uid user-id summary start duration (if all_day 1 0) desc loc calendar url (util/now-unix)])
      (when (seq tags)
        (tags/set-event-tags! user-id uid tags))
      (get-event user-id uid))))