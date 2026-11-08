(ns yc.calendar-test
  "Тесты ядра календаря (локальная история + теги + связь с заметками),
  без CalDAV и без модуля интеграции Яндекса."
  (:require [clojure.test :refer [deftest is testing]]
            [yc.db :as db]
            [yc.auth :as auth]
            [yc.tags :as tags]
            [yc.notes :as notes]
            [yc.calendar :as calendar]))

(defn- test-user []
  (let [login (str "ev" (rand-int 100000) "@test.ru")]
    (auth/create-user! {:login login :password "p"})
    (:user_id (auth/verify-token (str "Bearer " (:token (auth/login! {:login login :password "p"})))))))

(defn- seed-event! [uid user-id]
  (db/execute!
    ["INSERT OR REPLACE INTO events
      (uid, user_id, summary, start, duration, all_day, desc, loc, calendar_href, url, created_at)
      VALUES (?,?,?,?,?,?,?,?,?,?,?)"
     uid user-id "Тестовое событие" "2026-12-01 12:00" "60 мин" 0 "описание" ""
     "/cal" (str "/cal/" uid ".ics") 1700000000]))

(deftest event-tags
  (let [user-id (test-user)
        uid "event-uid-test-1"]
    (seed-event! uid user-id)
    ;; теги события
    (tags/set-event-tags! user-id uid ["работа" "важное"])
    (is (= ["важное" "работа"] (sort (tags/tags-of-event uid))))
    ;; список событий: все
    (is (= 1 (count (calendar/list-events user-id))))
    ;; фильтр по тегу события
    (is (= 1 (count (calendar/list-events user-id nil "работа"))))
    (is (= 0 (count (calendar/list-events user-id nil "нет-такого"))))
    ;; get-event с тегами
    (is (= ["важное" "работа"] (sort (:tags (calendar/get-event user-id uid)))))))

(deftest event-note-link
  (let [user-id (test-user)
        uid "event-uid-test-2"]
    (seed-event! uid user-id)
    ;; заметка, привязанная к событию
    (let [n (notes/create-note! user-id {:title "Заметка к событию" :event_id uid})]
      (is (= uid (:event_id n)))
      ;; событие видит связанную заметку
      (let [ev (calendar/get-event user-id uid)]
        (is (= [(:id n)] (:notes ev))))
      ;; обратная связь
      (is (= 1 (count (db/query ["SELECT id FROM notes WHERE event_id=?" uid])))))))