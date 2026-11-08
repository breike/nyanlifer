(ns yc.calendar
  "Ядро календаря: события хранятся локально в SQLite, с тегами и связью
  с заметками. Самодостаточно — никаких внешних сервисов. Удалённая
  синхронизация (Яндекс.Календарь) — опциональный модуль интеграции,
  который лишь дописывает поля url/calendar_href через apply-remote!."
  (:require [yc.db :as db]
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

(defn create-event!
  "Создаёт событие локально. Вход: {summary, start, duration?, desc?, loc?,
  tags?, calendar_href?}. Возвращает сохранённое событие (с тегами, notes: [])."
  [user-id {:keys [summary start duration desc loc calendar_href tags]}]
  (let [uid (util/uuid-hex)
        now (util/now-unix)
        all-day? (boolean (re-matches #"\d{4}-\d{2}-\d{2}" (clojure.string/trim (str start))))
        start-s (or start (str (java.time.LocalDate/now)))
        duration-s (if all-day?
                     "весь день"
                     (str (or duration 60) " мин"))
        row (db/insert!
              ["INSERT INTO events
                (uid, user_id, summary, start, duration, all_day, desc, loc, calendar_href, url, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)"
               uid user-id (or summary "") start-s duration-s (if all-day? 1 0)
               (or desc "") (or loc "") calendar_href nil now])]
    (when (seq tags)
      (tags/set-event-tags! user-id uid tags))
    (get-event user-id uid)))

(defn apply-remote!
  "После успешного пуша на внешний календарь дописывает поля
  (url, calendar_href, нормализованные start/duration). Обновляет только
  локальную запись, теги не трогает."
  [user-id uid remote]
  (db/execute!
    ["UPDATE events SET url=?, calendar_href=?, start=COALESCE(?, start),
      duration=COALESCE(?, duration) WHERE uid=? AND user_id=?"
     (:url remote) (:calendar remote) (:start remote) (:duration remote) uid user-id])
  (get-event user-id uid))

(defn delete-event!
  "Удаляет событие локально (теги/связи каскадно). Возвращает true если было."
  [user-id uid]
  (pos? (db/execute! ["DELETE FROM events WHERE uid=? AND user_id=?" uid user-id])))