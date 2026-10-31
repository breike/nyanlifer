(ns yc.tags-notes-test
  (:require [clojure.test :refer [deftest is testing]]
            [yc.db :as db]
            [yc.auth :as auth]
            [yc.tags :as tags]
            [yc.notes :as notes]))

;; Для тестов нужен изолированный DB. yc.db конфигурируется env YC_DB_PATH.
;; Задаём его до require yc.db (см. project.clj :test-selectors).

(deftest notes-crud
  (testing "notes CRUD with tags"
    (let [u (auth/create-user! {:login "n@test.ru" :password "p"})
          uid (:user_id (auth/verify-token (str "Bearer " (:token (auth/login! {:login "n@test.ru" :password "p"})))))]
      (is (some? uid))
      (let [n1 (notes/create-note! uid {:title "Заметка 1" :body "текст" :tags ["важное" "работа"]})]
        (is (= "Заметка 1" (:title n1)))
        (is (= ["важное" "работа"] (:tags n1)))
        (is (= ["важное" "работа"] (sort (:tags (notes/get-note uid (:id n1))))))
        ;; поиск
        (is (= 1 (count (notes/list-notes uid "текст" nil))))
        (is (= 1 (count (notes/list-notes uid nil "важное"))))
        ;; апдейт
        (let [n2 (notes/update-note! uid (:id n1) {:title "Новый" :tags ["личное"]})]
          (is (= "Новый" (:title n2)))
          (is (= ["личное"] (:tags n2))))
        ;; удаление
        (is (true? (notes/delete-note! uid (:id n1))))
        (is (nil? (notes/get-note uid (:id n1))))))))

(deftest tag-filter
  (let [u (auth/create-user! {:login "tag@test.ru" :password "p"})
        uid (:user_id (auth/verify-token (str "Bearer " (:token (auth/login! {:login "tag@test.ru" :password "p"})))))]
    (notes/create-note! uid {:title "A" :tags ["x"]})
    (notes/create-note! uid {:title "B" :tags ["y"]})
    (notes/create-note! uid {:title "C" :tags ["x" "y"]})
    (is (= 2 (count (notes/list-notes uid nil "x"))))
    (is (= 2 (count (notes/list-notes uid nil "y"))))
    (is (= 0 (count (notes/list-notes uid nil "z"))))))