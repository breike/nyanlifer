(ns yc.db
  "SQLite на чистом JDBC: соединение на вызов, хелперы query/execute!/insert!/with-tx.
  Схема: users, tokens, events, notes, tags, note_tags, event_tags."
  (:import (java.sql DriverManager ResultSet Statement)))

(defn -db-path []
  (or (System/getProperty "YC_DB_PATH")
      (System/getenv "YC_DB_PATH")
      "data/db.sqlite"))

(defn with-conn
  "Открыть свежее соединение (FK + busy_timeout), выполнить f, закрыть."
  [f]
  (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" (-db-path)))]
    (let [stmt (.createStatement conn)]
      (.execute stmt "PRAGMA foreign_keys = ON")
      (.execute stmt "PRAGMA busy_timeout = 5000")
      (.close stmt))
    (f conn)))

(defn- rows->maps
  [^ResultSet rs]
  (let [meta (.getMetaData rs)
        n (.getColumnCount meta)
        cols (mapv #(keyword (.getColumnLabel meta %)) (range 1 (inc n)))]
    (loop [acc []]
      (if (.next rs)
        (recur (conj acc (zipmap cols (mapv (fn [i] (.getObject rs i)) (range 1 (inc n))))))
        acc))))

(defn- normalize
  "Колл-сайты зовут (query \"SELECT ...\" a b) ИЛИ (query [\"SELECT ...\" a b]) — приводим к [sql params]."
  [stmt params]
  (if (sequential? stmt)
    [(first stmt) (rest stmt)]
    [stmt params]))

(defn- bind! [^java.sql.PreparedStatement p params]
  (doseq [[i v] (map-indexed (fn [i v] [(inc i) v]) params)]
    (.setObject p i v)))

(defn query
  [stmt & params]
  (let [[sql ps] (normalize stmt params)]
    (with-conn
      (fn [conn]
        (with-open [p (.prepareStatement conn sql)]
          (bind! p ps)
          (with-open [rs (.executeQuery p)]
            (rows->maps rs)))))))

(defn execute!
  "Выполнить модификацию; вернуть число затронутых строк."
  [stmt & params]
  (let [[sql ps] (normalize stmt params)]
    (with-conn
      (fn [conn]
        (with-open [p (.prepareStatement conn sql)]
          (bind! p ps)
          (.executeUpdate p))))))

(defn insert!
  "INSERT: [sql param...] -> rowid последней вставки (Long).
  sqlite-jdbc не поддерживает RETURN_GENERATED_KEYS — берём last_insert_rowid()
  на том же соединении."
  [stmt]
  (let [sql (first stmt) ps (rest stmt)]
    (with-conn
      (fn [conn]
        (with-open [p (.prepareStatement conn sql)]
          (bind! p ps)
          (.executeUpdate p))
        (with-open [s (.createStatement conn)
                    rs (.executeQuery s "SELECT last_insert_rowid() AS id")]
          (if (.next rs) (.getLong rs 1) nil))))))

(defn with-tx
  "Выполнить последовательность [sql param...] в одной транзакции."
  [statements]
  (with-conn
    (fn [conn]
      (let [auto (.getAutoCommit conn)]
        (.setAutoCommit conn false)
        (try
          (doseq [stmt statements]
            (let [sql (first stmt) ps (rest stmt)]
              (with-open [p (.prepareStatement conn sql)]
                (bind! p ps)
                (.executeUpdate p))))
          (.commit conn)
          (catch Throwable t
            (.rollback conn)
            (throw t))
          (finally (.setAutoCommit conn auto)))))))

;; ---------- схема ----------

(def schema
  ["CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      login TEXT UNIQUE NOT NULL,
      password_hash TEXT NOT NULL,
      created_at INTEGER)"
   "CREATE TABLE IF NOT EXISTS tokens (
      token_hash TEXT PRIMARY KEY,
      user_id INTEGER NOT NULL REFERENCES users(id),
      expires_at INTEGER,
      created_at INTEGER)"
   "CREATE TABLE IF NOT EXISTS events (
      uid TEXT PRIMARY KEY,
      user_id INTEGER NOT NULL,
      summary TEXT,
      start TEXT,
      duration TEXT,
      all_day INTEGER,
      desc TEXT,
      loc TEXT,
      calendar_href TEXT,
      url TEXT,
      created_at INTEGER)"
   "CREATE TABLE IF NOT EXISTS notes (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      title TEXT NOT NULL,
      body TEXT NOT NULL,
      event_id TEXT,
      created_at INTEGER,
      updated_at INTEGER)"
   "CREATE TABLE IF NOT EXISTS tags (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      name TEXT NOT NULL,
      UNIQUE(user_id, name))"
   "CREATE TABLE IF NOT EXISTS note_tags (
      note_id INTEGER NOT NULL REFERENCES notes(id) ON DELETE CASCADE,
      tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
      PRIMARY KEY(note_id, tag_id))"
   "CREATE TABLE IF NOT EXISTS event_tags (
      event_uid TEXT NOT NULL REFERENCES events(uid) ON DELETE CASCADE,
      tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
      PRIMARY KEY(event_uid, tag_id))"
   "CREATE INDEX IF NOT EXISTS idx_notes_user ON notes(user_id)"
   "CREATE INDEX IF NOT EXISTS idx_notes_event ON notes(event_id)"
   "CREATE INDEX IF NOT EXISTS idx_events_user ON events(user_id)"
   "CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens(user_id)"])

(defn init-db!
  []
  (with-conn
    (fn [conn]
      (let [stmt (.createStatement conn)]
        (try
          (doseq [ddl schema]
            (.execute stmt ddl))
          (finally (.close stmt)))))))