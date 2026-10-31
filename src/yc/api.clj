(ns yc.api
  "HTTP API (compojure + ring). JSON через cheshire. Bearer-проверка."
  (:require [compojure.core :refer [defroutes routes GET POST PUT DELETE ANY context]]
            [compojure.route :as route]
            [ring.util.response :as resp]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [yc.auth :as auth]
            [yc.tags :as tags]
            [yc.notes :as notes]
            [yc.events :as events]
            [yc.calendars :as cal])
  (:import (java.io File)))

;; ---------- helpers ----------

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body (json/generate-string body)})

(defn- unauthorized []
  (json-response 401 {:error "unauthorized"}))

(defn- require-user
  "request -> user map или nil (не авторизован)."
  [request]
  (auth/verify-token (get-in request [:headers "authorization"] "")))

(defn- read-body
  "Читает :body если InputStream -> string."
  [request]
  (let [b (:body request)]
    (cond
      (instance? java.io.InputStream b)
      (slurp b)
      (string? b) b
      :else nil)))

(defn- body-params
  "Параметры из JSON-тела (string/InputStream/map)."
  [request]
  (or (:json-params request)
      (let [s (read-body request)]
        (cond
          (map? (:body request)) (:body request)
          (and s (seq (str/trim s)))
          (try (json/parse-string s true) (catch Exception _ {}))
          :else {}))))

(defn- param
  "Именованный параметр: из body или query."
  [request k]
  (or (get (body-params request) k)
      (get-in request [:params k])
      (get-in request [:form-params k])))

;; ---------- routes ----------

(defroutes app-routes

  (GET "/health" [] (json-response 200 {:status "ok"}))

  ;; ---- auth ----
  (POST "/register" request
    (let [{:keys [login password]} (body-params request)]
      (cond
        (not (seq (str/trim (str login)))) (json-response 400 {:error "login обязателен"})
        (not (seq (str password))) (json-response 400 {:error "password обязателен"})
        :else (if-let [user (auth/create-user! {:login login :password password})]
                (json-response 201 user)
                (json-response 409 {:error "Пользователь уже существует"})))))
  (POST "/login" request
    (let [{:keys [login password]} (body-params request)
          res (auth/login! {:login login :password password})]
      (if res
        (json-response 200 res)
        (json-response 401 {:error "Неверный логин или пароль"}))))
  (POST "/logout" request
    (auth/logout! (get-in request [:headers "authorization"] ""))
    (json-response 200 {:ok true}))
  (POST "/verify" request
    (if-let [user (require-user request)]
      (json-response 200 user)
      (unauthorized)))

  ;; ---- tags ----
  (GET "/tags" request
    (if-let [user (require-user request)]
      (json-response 200 {:tags (tags/list-tags (:user_id user))})
      (unauthorized)))
  (POST "/tags" request
    (if-let [user (require-user request)]
      (let [name (param request :name)]
        (if (seq (str/trim (str name)))
          (json-response 201 (tags/ensure-tag! (:user_id user) name))
          (json-response 400 {:error "name обязателен"})))
      (unauthorized)))
  (DELETE "/tags/:id" [id :as request]
    (if-let [user (require-user request)]
      (if (tags/delete-tag! (:user_id user) (Long/parseLong id))
        (json-response 200 {:ok true})
        (json-response 404 {:error "Тег не найден"}))
      (unauthorized)))

  ;; ---- notes ----
  (GET "/notes" [q tag :as request]
    (if-let [user (require-user request)]
      (json-response 200 {:notes (notes/list-notes (:user_id user) q tag)})
      (unauthorized)))
  (POST "/notes" request
    (if-let [user (require-user request)]
      (let [{:keys [title] :as p} (body-params request)]
        (if (seq (str/trim (str title)))
          (json-response 201 (notes/create-note! (:user_id user) p))
          (json-response 400 {:error "title обязателен"})))
      (unauthorized)))
  (GET "/notes/:id" [id :as request]
    (if-let [user (require-user request)]
      (if-let [note (notes/get-note (:user_id user) (Long/parseLong id))]
        (json-response 200 note)
        (json-response 404 {:error "Заметка не найдена"}))
      (unauthorized)))
  (PUT "/notes/:id" [id :as request]
    (if-let [user (require-user request)]
      (if-let [note (notes/update-note! (:user_id user) (Long/parseLong id) (body-params request))]
        (json-response 200 note)
        (json-response 404 {:error "Заметка не найдена"}))
      (unauthorized)))
  (DELETE "/notes/:id" [id :as request]
    (if-let [user (require-user request)]
      (if (notes/delete-note! (:user_id user) (Long/parseLong id))
        (json-response 200 {:ok true})
        (json-response 404 {:error "Заметка не найдена"}))
      (unauthorized)))

  ;; ---- events ----
  (GET "/calendars" request
    (if-let [user (require-user request)]
      (if-let [cred (events/yandex-credentials)]
        (try
          (json-response 200 {:calendars (cal/list-calendars (:server cred)
                                                              (:login cred)
                                                              (:password cred))})
          (catch clojure.lang.ExceptionInfo e
            (let [{:keys [yc/error status]} (ex-data e)]
              (case error
                :auth (json-response 401 {:error "Не удалось авторизоваться в Яндекс.Календаре"})
                :http (json-response 502 {:error (str "Ошибка CalDAV: HTTP " status)})
                :network (json-response 502 {:error (ex-message e)})
                :none (json-response 404 {:error (ex-message e)})
                (json-response 500 {:error (ex-message e)}))))
          (catch Exception e
            (json-response 500 {:error (ex-message e)})))
        (json-response 500 {:error "YANDEX_CALDAV_LOGIN/PASSWORD не заданы"}))
      (unauthorized)))
  (GET "/events" [q tag :as request]
    (if-let [user (require-user request)]
      (json-response 200 {:events (events/list-events (:user_id user) q tag)})
      (unauthorized)))
  (POST "/events" request
    (if-let [user (require-user request)]
      (let [p (body-params request)]
        (if (seq (str/trim (str (:summary p))))
          (try
            (json-response 201 (events/create-event! (:user_id user) p))
            (catch clojure.lang.ExceptionInfo e
              (let [{:keys [yc/error status]} (ex-data e)]
                (case error
                  :config (json-response 500 {:error "YANDEX_CALDAV_LOGIN/PASSWORD не заданы"})
                  :auth (json-response 401 {:error "Не удалось авторизоваться в Яндекс.Календаре"})
                  :http (json-response 502 {:error (str "Ошибка CalDAV: HTTP " status)})
                  :none (json-response 404 {:error (ex-message e)})
                  (json-response 500 {:error (ex-message e)}))))
            (catch Exception e
              (json-response 500 {:error (ex-message e)})))
          (json-response 400 {:error "summary обязателен"})))
      (unauthorized)))
  (GET "/events/:uid" [uid :as request]
    (if-let [user (require-user request)]
      (if-let [event (events/get-event (:user_id user) uid)]
        (json-response 200 event)
        (json-response 404 {:error "Событие не найдено"}))
      (unauthorized)))

  ;; события -> связанные заметки
  (GET "/events/:uid/notes" [uid :as request]
    (if-let [user (require-user request)]
      (if-let [event (events/get-event (:user_id user) uid)]
        (json-response 200 {:notes (mapv #(notes/get-note (:user_id user) %)
                                         (:notes event))})
        (json-response 404 {:error "Событие не найдено"}))
      (unauthorized))))

(defn- static-resource
  "Читает файл из classpath-ресурса public/<path>, либо с диска resources/public/<path>.
  Возвращает nil, если файла нет."
  [path]
  (let [rel (str "public/" path)
        url (io/resource rel)
        f (File. "resources" rel)]
    (cond
      url (with-open [s (.openStream ^java.net.URL url)] (slurp s))
      (.isFile f) (with-open [s (java.io.FileInputStream. f)] (slurp s))
      :else nil)))

(defn- static-file
  "Отдать файл из resources/public (или index.html для SPA fallback).
  Работает и из jar (classpath-resource), и с диска."
  [path]
  (let [name (if (or (nil? path) (= path "/") (= path "")) "index.html" path)
        content (static-resource name)]
    (if content
      (-> (resp/response content)
          (resp/content-type (case (re-find #"\.\w+$" name)
                               ".js" "application/javascript"
                               ".css" "text/css"
                               ".html" "text/html; charset=utf-8"
                               "application/octet-stream")))
      {:status 404 :headers {"Content-Type" "text/plain"} :body "Not Found"})))

(def ^:private static-handler
  "SPA static: / -> index.html, /js/* -> файл, прочее -> index.html (fallback)."
  (routes
    (GET "/" [] (static-file nil))
    (GET "/js/main.js" [] (static-file "js/main.js"))
    (GET "/js/:file" [file] (static-file (str "js/" file)))))

(def app
  (-> (routes app-routes static-handler
              (route/not-found (json-response 404 {:error "Not Found"})))
      ;; NB: ring-json 0.5.1 wrap-json-body сбрасывал headers — избегаем его,
      ;; парсим тело самостоятельно при необходимости.
      (wrap-json-response)
      (wrap-keyword-params)
      (wrap-params)))