(ns yc.util
  "Утилиты: хеши, base64, время, uuid, ICS-escape."
  (:require [clojure.string :as str])
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64 UUID)
           (java.time Instant)))

(defn sha256-hex
  "SHA-256 строки в hex (для хеширования токенов/сравнений)."
  [s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes ^String s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn pbkdf2-hex
  "PBKDF2WithHmacSHA256(salt, password, 100000) -> hex-строка."
  ^String [^String password ^String salt]
  (let [spec (javax.crypto.spec.PBEKeySpec.
               (.toCharArray password) (.getBytes salt "UTF-8") 100000 256)
        factory (javax.crypto.SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
        bytes (.getEncoded (.generateSecret factory spec))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn hash-password
  "Хранимый формат: salt$pdkdf2-hex."
  [password]
  (let [salt (format "%064x" (BigInteger. 1 (.generateSeed (SecureRandom.) 32)))]
    (str salt "$" (pbkdf2-hex password salt))))

(defn verify-password?
  "Проверка пароля против сохранённого 'salt$hex'."
  [password stored]
  (when-let [[_ salt hex] (re-matches #"^([0-9a-f]+)\$([0-9a-f]+)$" (str stored))]
    (let [calc (pbkdf2-hex password salt)]
      (MessageDigest/isEqual (.getBytes calc "UTF-8") (.getBytes hex "UTF-8")))))

(defn random-token
  "Случайный URL-safe токен (32 байта)."
  []
  (let [bytes (.generateSeed (SecureRandom.) 32)
        enc (.withoutPadding (Base64/getUrlEncoder))]
    (.encodeToString enc bytes)))

(defn now-unix
  "Текущее unix-время (сек)."
  []
  (.getEpochSecond (Instant/now)))

(defn uuid-hex
  "hex-uid без дефисов (для UID события / id)."
  []
  (str/replace (str (UUID/randomUUID)) "-" ""))

(defn ics-escape
  "Экранирование строки для ICS (обратный слэш, ; и ,)."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace ";" "\\;")
      (str/replace "," "\\,")
      (str/replace "\n" "\\n")))

(defn base64-basic
  "Basic-заголовок авторизации для CalDAV."
  [login password]
  (str "Basic "
       (.encodeToString (Base64/getEncoder)
                        (.getBytes (str login ":" password) "UTF-8"))))