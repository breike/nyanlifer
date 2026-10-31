(ns yc.auth
  "Регистрация, токены, проверка Bearer. Портируем auth-service (Python)."
  (:require [yc.db :as db]
            [yc.util :as util])
  (:import (java.time Instant))
  (:require [clojure.string :as str]))

(def token-ttl
  (Long/parseLong (or (System/getenv "AUTH_TOKEN_TTL") "86400")))

(defn create-user!
  "Регистрация: login (lowercase), password. Возвращает {:login ...} или nil если занят."
  [{:keys [login password]}]
  (let [login (-> login str clojure.string/trim str/lower-case)]
    (try
      (db/execute!
        ["INSERT INTO users (login, password_hash, created_at) VALUES (?, ?, ?)"
         login (util/hash-password password) (util/now-unix)])
      {:login login}
      (catch Exception _ nil))))

(defn login!
  "Валидные логин+пароль -> {:token .. :expires_at ..}. Иначе nil."
  [{:keys [login password]}]
  (let [login (-> login str clojure.string/trim str/lower-case)]
    (when-let [row (first (db/query
                            ["SELECT id, password_hash FROM users WHERE login=?" login]))]
      (when (util/verify-password? password (:password_hash row))
        (let [token (util/random-token)
              token-hash (util/sha256-hex token)
              expires (+ (util/now-unix) token-ttl)]
          (db/execute!
            ["INSERT INTO tokens (token_hash, user_id, expires_at, created_at) VALUES (?,?,?,?)"
             token-hash (:id row) expires (util/now-unix)])
          {:token token :expires_at expires})))))

(defn verify-token
  "Bearer-строка или token -> {:user_id .. :login ..} или nil."
  [authorization]
  (let [token (if (and authorization (.startsWith ^String authorization "Bearer "))
                (clojure.string/trim (subs authorization 7))
                (str authorization))]
    (when (seq token)
      (let [token-hash (util/sha256-hex token)
            now (util/now-unix)]
        (first (db/query
                 ["SELECT u.id AS user_id, u.login FROM tokens t
                   JOIN users u ON u.id = t.user_id
                   WHERE t.token_hash=? AND t.expires_at>?"
                  token-hash now]))))))

(defn logout!
  "Отозвать токен."
  [authorization]
  (let [token (if (and authorization (.startsWith ^String authorization "Bearer "))
                (clojure.string/trim (subs authorization 7))
                (str authorization))]
    (when (seq token)
      (db/execute! ["DELETE FROM tokens WHERE token_hash=?" (util/sha256-hex token)])
      {:ok true})
    {:ok true}))