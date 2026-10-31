(ns yc.tags
  "Теги: CRUD, привязка к заметкам и событиям, автосоздание по имени."
  (:require [yc.db :as db]))

(defn ensure-tag!
  "Найти тег по имени для user, создать если нет. Возвращает {:id .. :name ..}."
  [user-id name]
  (let [name (clojure.string/trim (str name))]
    (when (seq name)
      (or (first (db/query ["SELECT id, name FROM tags WHERE user_id=? AND name=?"
                            user-id name]))
          (let [id (db/insert! ["INSERT INTO tags (user_id, name) VALUES (?, ?)"
                                user-id name])]
            {:id id :name name})))))

(defn set-note-tags!
  "Перезаписать теги заметки: +привязки, -отвязанные."
  [user-id note-id tag-names]
  (db/execute! ["DELETE FROM note_tags WHERE note_id=?" note-id])
  (doseq [name (distinct tag-names)]
    (when-let [tag (ensure-tag! user-id name)]
      (db/execute! ["INSERT OR IGNORE INTO note_tags (note_id, tag_id) VALUES (?, ?)"
                    note-id (:id tag)]))))

(defn set-event-tags!
  "Перезаписать теги события (по uid)."
  [user-id event-uid tag-names]
  (db/execute! ["DELETE FROM event_tags WHERE event_uid=?" event-uid])
  (doseq [name (distinct tag-names)]
    (when-let [tag (ensure-tag! user-id name)]
      (db/execute! ["INSERT OR IGNORE INTO event_tags (event_uid, tag_id) VALUES (?, ?)"
                    event-uid (:id tag)]))))

(defn tags-of-note
  [note-id]
  (mapv :name (db/query
                ["SELECT t.name FROM note_tags nt JOIN tags t ON t.id=nt.tag_id
                  WHERE nt.note_id=? ORDER BY t.name" note-id])))

(defn tags-of-event
  [event-uid]
  (mapv :name (db/query
                ["SELECT t.name FROM event_tags et JOIN tags t ON t.id=et.tag_id
                  WHERE et.event_uid=? ORDER BY t.name" event-uid])))

(defn note-ids-by-tag
  "IDs заметок пользователя, у которых есть тег name."
  [user-id tag-name]
  (mapv :note_id (db/query
                   ["SELECT nt.note_id FROM note_tags nt
                     JOIN tags t ON t.id=nt.tag_id
                     JOIN notes n ON n.id=nt.note_id
                     WHERE n.user_id=? AND t.name=?" user-id tag-name])))

(defn event-uids-by-tag
  [user-id tag-name]
  (mapv :event_uid (db/query
                     ["SELECT et.event_uid FROM event_tags et
                       JOIN tags t ON t.id=et.tag_id
                       JOIN events e ON e.uid=et.event_uid
                       WHERE e.user_id=? AND t.name=?" user-id tag-name])))

(defn list-tags
  [user-id]
  (mapv #(assoc % :name (str (:name %)))
        (db/query ["SELECT id, name FROM tags WHERE user_id=? ORDER BY name" user-id])))

(defn delete-tag!
  [user-id tag-id]
  (let [n (first (db/query ["SELECT id FROM tags WHERE id=? AND user_id=?" tag-id user-id]))]
    (when n
      (db/execute! ["DELETE FROM tags WHERE id=?" tag-id])
      true)))