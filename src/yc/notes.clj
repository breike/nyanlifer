(ns yc.notes
  "Заметки: CRUD, связь с событием (event_id), поиск, теги."
  (:require [yc.db :as db]
            [yc.tags :as tags]
            [yc.markdown :as md]
            [yc.util :as util]))

(defn note-row->map
  "row -> map с тегами и HTML-версией body (для рендера на фронте)."
  [row]
  (when row
    (let [id (:id row)]
      (assoc (into {} row)
             :tags (tags/tags-of-note id)
             :body_html (md/md->html (:body row))))))

(defn list-notes
  ([user-id] (list-notes user-id nil nil))
  ([user-id q tag-name]
   (let [sql (str "SELECT * FROM notes WHERE user_id=?"
                  (when q " AND (title LIKE ? OR body LIKE ?)")
                  (when tag-name " AND id IN (SELECT note_id FROM note_tags nt JOIN tags t ON t.id=nt.tag_id WHERE t.name=?)")
                  " ORDER BY updated_at DESC")
         params (cond-> [user-id]
                  q (conj (str "%" q "%") (str "%" q "%"))
                  tag-name (conj tag-name))]
     (mapv note-row->map (db/query (vec (cons sql params)))))))

(defn get-note
  [user-id note-id]
  (note-row->map (first (db/query ["SELECT * FROM notes WHERE id=? AND user_id=?" note-id user-id]))))

(defn create-note!
  [user-id {:keys [title body event_id tags]}]
  (let [now (util/now-unix)
        id (db/insert! ["INSERT INTO notes (user_id, title, body, event_id, created_at, updated_at)
        VALUES (?,?,?,?,?,?)"
                        user-id title (or body "") event_id now now])]
    (when (seq tags)
      (tags/set-note-tags! user-id id tags))
    (get-note user-id id)))

(defn update-note!
  [user-id note-id {:keys [title body event_id tags]}]
  (let [row (first (db/query ["SELECT * FROM notes WHERE id=? AND user_id=?" note-id user-id]))]
    (when row
      (db/execute!
        ["UPDATE notes SET title=?, body=?, event_id=?, updated_at=? WHERE id=?"
         (if (some? title) title (:title row))
         (if (some? body) body (:body row))
         (if (some? event_id) event_id (:event_id row))
         (util/now-unix) note-id])
      (when (some? tags)
        (tags/set-note-tags! user-id note-id tags))
      (get-note user-id note-id))))

(defn delete-note!
  [user-id note-id]
  (pos? (db/execute! ["DELETE FROM notes WHERE id=? AND user_id=?" note-id user-id])))