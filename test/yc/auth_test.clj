(ns yc.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [yc.db :as db]
            [yc.auth :as auth]))

(def ^:dynamic *tmp-db* "data/test-auth.db")

(defn fixture-db [f]
  (println "fixture: " *tmp-db*)
  (f))

(deftest hash-password-roundtrip
  (let [h (yc.util/hash-password "secret")]
    (is (yc.util/verify-password? "secret" h))
    (is (not (yc.util/verify-password? "wrong" h)))
    (is (not (yc.util/verify-password? "secret" "garbage")))))

(deftest register-login-flow
  (testing "register then login"
    (let [res (auth/create-user! {:login "user@test.ru" :password "app-pass"})]
      (is (= {:login "user@test.ru"} (select-keys res [:login]))))
    (let [tok (auth/login! {:login "user@test.ru" :password "app-pass"})]
      (is (some? tok))
      (is (string? (:token tok))))
    (is (nil? (auth/login! {:login "user@test.ru" :password "bad"}))))
  (testing "duplicate register"
    (is (nil? (auth/create-user! {:login "user@test.ru" :password "other"})))))

(deftest verify-token
  (let [_ (auth/create-user! {:login "v@test.ru" :password "p"})
        {:keys [token]} (auth/login! {:login "v@test.ru" :password "p"})]
    (let [u (auth/verify-token (str "Bearer " token))]
      (is (some? u))
      (is (= "v@test.ru" (:login u)))
      (is (int? (:user_id u))))
    (is (nil? (auth/verify-token "Bearer nope")))
    (is (nil? (auth/verify-token "")))
    (is (nil? (auth/verify-token nil)))))

(deftest logout-revokes
  (let [_ (auth/create-user! {:login "l@test.ru" :password "p"})
        {:keys [token]} (auth/login! {:login "l@test.ru" :password "p"})]
    (is (some? (auth/verify-token (str "Bearer " token))))
    (auth/logout! (str "Bearer " token))
    (is (nil? (auth/verify-token (str "Bearer " token))))))

(deftest auth-multi-user
  (auth/create-user! {:login "a@test.ru" :password "p"})
  (auth/create-user! {:login "b@test.ru" :password "p"})
  (let [ta (:token (auth/login! {:login "a@test.ru" :password "p"}))
        tb (:token (auth/login! {:login "b@test.ru" :password "p"}))]
    (is (= "a@test.ru" (:login (auth/verify-token (str "Bearer " ta)))))
    (is (= "b@test.ru" (:login (auth/verify-token (str "Bearer " tb)))))))