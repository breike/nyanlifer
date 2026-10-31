(ns yc.test-runner
  "Запуск всех тестов с изолированной БД."
  (:require [clojure.test :as t]))

(defn -main
  [& _args]
  ;; Тестовая БД: свежий файл в data/
  (System/setProperty "YC_DB_PATH" "data/test.db")
  ;; Удалить старую тестовую БД, чтобы тесты были идемпотентны
  (let [f (java.io.File. "data/test.db")]
    (when (.exists f) (.delete f)))
  ;; Сначала инициализировать схему на тестовой БД
  (require 'yc.db)
  ((resolve 'yc.db/init-db!))
  ;; Теперь загрузить модули (используют db)
  (require 'yc.auth-test 'yc.tags-notes-test 'yc.events-test 'yc.api-test)
  (let [result (t/run-tests 'yc.auth-test 'yc.tags-notes-test 'yc.events-test 'yc.api-test)]
    (System/exit (if (and (zero? (:fail result)) (zero? (:error result))) 0 1))))