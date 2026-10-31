(ns yc.api-test
  "HTTP-тесты через ring-mock: полный API-флоу."
  (:require [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]
            [yc.api :as api]))

(defn- req [method path token body]
  (let [r (mock/request method path)
        r (if token (mock/header r "Authorization" (str "Bearer " token)) r)
        r (if body (mock/json-body r body) r)]
    r))

(defn- body [resp] (json/parse-string (:body resp) true))

(deftest full-flow
  (testing "register/login/tags/notes"
    (is (= 201 (:status (api/app (req :post "/register" nil {:login "api@test.ru" :password "p"})))))
    (let [login (api/app (req :post "/login" nil {:login "api@test.ru" :password "p"}))
          token (get-in (body login) [:token])]
      (is (= 200 (:status login)))
      (is (string? token))

      ;; unauthorized
      (is (= 401 (:status (api/app (req :get "/notes" nil nil)))))

      ;; create tag
      (let [r (api/app (req :post "/tags" token {:name "важное"}))]
        (is (= 201 (:status r))))

      ;; create note with tag + event_id
      (let [r (api/app (req :post "/notes" token {:title "Заметка" :body "текст"
                                                  :tags ["важное"] :event_id "evt-1"}))]
        (is (= 201 (:status r)))
        (let [note (body r)]
          (is (= ["важное"] (:tags note)))
          (is (= "evt-1" (:event_id note)))
          (def created-id (:id note))))

      ;; list by tag
      (let [r (api/app (req :get "/notes?tag=важное" token nil))]
        (is (= 200 (:status r)))
        (is (= 1 (count (:notes (body r))))))

      ;; notes list
      (let [r (api/app (req :get "/notes" token nil))]
        (is (= 1 (count (:notes (body r))))))

      ;; get note
      (let [r (api/app (req :get (str "/notes/" created-id) token nil))]
        (is (= 200 (:status r)))
        (is (= "Заметка" (:title (body r))))))))