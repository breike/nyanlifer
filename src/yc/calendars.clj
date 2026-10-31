(ns yc.calendars
  "CalDAV-клиент Яндекс.Календаря. Порт shared/caldav_client.py; stdlib-only."
  (:require [clojure.string :as str]
            [clojure.xml :as xml]
            [yc.util :as util])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse
                        HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration Instant ZoneId ZonedDateTime LocalDate LocalDateTime)
           (java.util UUID)))

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 30))
             .build)))

(def default-server "https://caldav.yandex.ru")
(def ^:private ns-d "DAV:")
(def ^:private ns-c "urn:ietf:params:xml:ns:caldav")

;; ---------- низкий HTTP (java.net.http: поддерживает WebDAV-методы) ----------

(defn- http-request
  "PROPFIND/PUT к CalDAV-серверу. Возвращает {:status .. :body ..}.
  Сетевые ошибки бросают ExceptionInfo; HTTP-статус НЕ бросает."
  [server login password method path body headers timeout-ms]
  (let [url (if (or (.startsWith ^String path "http://")
                    (.startsWith ^String path "https://"))
              path
              (str server path))
        builder (doto (HttpRequest/newBuilder)
                  (.uri (URI/create url))
                  (.timeout (Duration/ofMillis (long timeout-ms)))
                  (.header "Authorization" (util/base64-basic login password))
                  (.header "User-Agent" "nyanlifer/0.1"))]
    (doseq [[k v] headers]
      (.header builder (str k) (str v)))
    (let [req (-> (if body
                    (.method builder (str method)
                             (HttpRequest$BodyPublishers/ofString body))
                    (.method builder (str method)
                             (HttpRequest$BodyPublishers/noBody)))
                  .build)]
      (try
        (let [resp (.send @http-client req (HttpResponse$BodyHandlers/ofString))]
          {:status (.statusCode resp) :body (.body resp)})
        (catch Exception e
          (throw (ex-info (str "Сетевая ошибка CalDAV: " (.getMessage e))
                          {:yc/error :network})))))))

(defn- propfind
  [server login password path depth body]
  (http-request server login password "PROPFIND" path body
                {"Depth" depth "Content-Type" "application/xml; charset=utf-8"}
                30000))

(defn- local-name
  "Локальное имя тега: :D:current-user-principal -> \"current-user-principal\"."
  [el]
  (-> el :tag name (str/split #":") last))

(defn- xml-tags
  "Все элементы, чьё локальное имя = tag-name (любые namespace/префиксы)."
  [root tag-name]
  (letfn [(walk [el]
            (when (map? el)
              (let [tag (local-name el)]
                (if (= tag tag-name)
                  (cons el (mapcat walk (:content el)))
                  (mapcat walk (:content el))))))]
    (vec (walk root))))

(defn- text-of
  "Текстовое содержимое элемента (объединяет строки/вложенные)."
  [el]
  (when el
    (let [c (:content el)]
      (if (sequential? c)
        (apply str (map #(if (map? %) (text-of %) (str %)) c))
        (str c)))))

(defn- xml-tags-href
  "Первый href внутри элемента (поиск по локальному имени)."
  [el]
  (when el
    (-> (xml-tags el "href") first text-of)))

(defn- xml-parse-body
  "Парсит XML-строку в clojure.xml дерево (nil при ошибке)."
  [body]
  (when body
    (try (xml/parse (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8")))
         (catch Exception _ nil))))

(defn- propfind-all
  "PROPFIND c Depth:1, вернуть {:status .. :hrefs [..] :names [..] :doc parsed}."
  [server login password path depth body]
  (let [{:keys [status body]} (propfind server login password path depth body)]
    (if (>= status 400)
      {:status status :hrefs [] :names [] :doc nil}
      (let [parsed (try (xml/parse (java.io.ByteArrayInputStream. (.getBytes body "UTF-8")))
                        (catch Exception _ nil))
            hrefs (if parsed
                    (->> (xml-tags parsed "href")
                         (map text-of))
                    [])
            names (if parsed
                    (->> (xml-tags parsed "displayname")
                         (map text-of))
                    [])]
        {:status status :hrefs hrefs :names names :doc parsed}))))

;; ---------- публичный API ----------

(def ^:private principal-body
  "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:current-user-principal/></d:prop></d:propfind>")

(def ^:private home-body
  "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop><c:calendar-home-set/></d:prop></d:propfind>")

(defn parse-event-start
  "'ГГГГ-ММ-ДД' -> {:kind :date :date (LocalDate)}
   'ГГГГ-ММ-ДД ЧЧ:ММ' -> {:kind :date-time :local (LocalDateTime)}"
  [s]
  (let [s (str/trim (str s))]
    (cond
      (re-matches #"\d{4}-\d{2}-\d{2}" s)
      {:kind :date :date (LocalDate/parse s)}

      (re-matches #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}" s)
      {:kind :date-time :local (LocalDateTime/parse (str/replace s " " "T"))}

      :else (throw (ex-info "Начало задавайте в формате ГГГГ-ММ-ДД [ЧЧ:ММ]"
                            {:input s})))))

(def ^:private ics-dt-fmt
  (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
  )

(def ^:private ics-date-fmt
  (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd"))

(defn build-ics
  "Собирает .ics-текст события."
  [{:keys [summary start-kind start-val end-val desc loc uid]}]
  (let [uid (or uid (.replace (str (UUID/randomUUID)) "-" ""))
        ts (-> (Instant/now) (.atZone (ZoneId/of "UTC")) (.format ics-dt-fmt))
        lines (concat
                ["BEGIN:VCALENDAR" "VERSION:2.0" "PRODID:-//nyanlifer//RU"
                 "BEGIN:VEVENT"
                 (str "UID:" uid)
                 (str "DTSTAMP:" ts)]
                (if (= start-kind :date)
                  [(str "DTSTART;VALUE=DATE:" (.format ^java.time.LocalDate start-val ics-date-fmt))
                   (str "DTEND;VALUE=DATE:" (.format ^java.time.LocalDate end-val ics-date-fmt))]
                  [(str "DTSTART:" (.format ^java.time.LocalDateTime start-val ics-dt-fmt))
                   (str "DTEND:" (.format ^java.time.LocalDateTime end-val ics-dt-fmt))])
                [(str "SUMMARY:" (util/ics-escape summary))]
                (when (seq desc) [(str "DESCRIPTION:" (util/ics-escape desc))])
                (when (seq loc) [(str "LOCATION:" (util/ics-escape loc))])
                ["END:VEVENT" "END:VCALENDAR"])]
    (str (str/join "\r\n" lines) "\r\n")))

(defn list-calendars
  "Возвращает [{(:href ..) (:name ..)} ...].
  Бросает ExceptionInfo с :yc/error :auth/:http/:none."
  [server login password]
  (let [check! (fn [res step]
                 (when (>= (:status res) 400)
                   (throw (ex-info (str "PROPFIND " step ": HTTP " (:status res))
                                   {:yc/error :http :status (:status res) :body (:body res)})))
                 res)
        principal (-> (check! (propfind server login password server "0" principal-body) "principal")
                      :body
                      (xml-parse-body)
                      (xml-tags "current-user-principal")
                      first
                      xml-tags-href)]
    (when-not principal
      (throw (ex-info "Не удалось определить principal" {:yc/error :none})))
    (let [home-url (str server "/" (str/replace principal #"^/" ""))
          home (-> (check! (propfind server login password home-url "0" home-body) "home-set")
                   :body
                   (xml-parse-body)
                   (xml-tags "calendar-home-set")
                   first
                   xml-tags-href)]
      (when-not home
        (throw (ex-info "Не найден calendar-home-set" {:yc/error :none})))
      (let [home-url (str server "/" (str/replace home #"^/" ""))
            calendars-body (check! (propfind server login password home-url "1"
                                             "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop><d:resourcetype/><d:displayname/></d:prop></d:propfind>")
                                   "calendars")
            doc (xml-parse-body (:body calendars-body))]
        (->> (xml-tags doc "response")
             (filter #(seq (xml-tags % "calendar")))
             (mapv (fn [resp] {:href (xml-tags-href resp)
                               :name (or (text-of (first (xml-tags resp "displayname"))) "")})))))))

(defn create-event
  "Создаёт событие в Яндекс.Календаре. start — 'ГГГГ-ММ-ДД [ЧЧ:ММ]'.
  duration_min — для date-time. Возвращает map события или nil при ошибке."
  [server login password summary start-s {:keys [duration_min desc loc calendar_href] :as opts}]
  (let [calendars (list-calendars server login password)
        selected (or (and calendar_href
                          (first (filter #(or (= (:href %) calendar_href)
                                              (= (str/replace (:href %) #"/$" "")
                                                 (str/replace calendar_href #"/$" "")))
                                         calendars)))
                     (first calendars))]
    (when-not selected
      (throw (ex-info "Нет доступных календарей" {:yc/error :none})))
    (let [{:keys [kind date local]} (parse-event-start start-s)
          duration-min (or duration_min 60)
          sys-zone (ZoneId/systemDefault)
          [start-val end-val all-day]
          (if (= kind :date)
            [date (.plusDays date 1) true]
            [(-> local (.atZone sys-zone) (.withZoneSameInstant (ZoneId/of "UTC")) (.toLocalDateTime))
             (-> local (.plusMinutes duration-min) (.atZone sys-zone)
                 (.withZoneSameInstant (ZoneId/of "UTC")) (.toLocalDateTime))
             false])
          uid (str/replace (str (UUID/randomUUID)) "-" "")
          ics (build-ics {:summary summary :start-kind (if all-day :date :date-time)
                          :start-val start-val :end-val end-val
                          :desc (or desc "") :loc (or loc "") :uid uid})
          event-url (str (if (.endsWith ^String (:href selected) "/")
                           (:href selected)
                           (str (:href selected) "/"))
                         uid ".ics")
          res (http-request server login password "PUT" event-url ics
                            {"Content-Type" "text/calendar; charset=utf-8"
                             "If-None-Match" "*"}
                            30000)]
      (if (contains? #{201 204} (:status res))
        {:uid uid :url event-url :summary summary
         :start (if all-day (str date)
                    (.format local (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm")))
         :duration (if all-day "весь день" (str duration-min " мин"))
         :all_day all-day :desc (or desc "") :loc (or loc "")
         :calendar (:href selected)}
        (throw (ex-info (str "PUT: HTTP " (:status res))
                        {:yc/error :http :status (:status res) :body (:body res)}))))))