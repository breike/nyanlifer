(ns yc.core
  "Точка входа: инициализация БД и запуск Jetty."
  (:gen-class)
  (:require [yc.db :as db]
            [yc.api :as api]
            [ring.adapter.jetty :as jetty]))

(defn -main
  [& _args]
  (db/init-db!)
  (let [port (Integer/parseInt (or (System/getenv "YC_PORT") "8000"))]
    (println "nyanlifer: http://127.0.0.1:" port)
    (jetty/run-jetty api/app {:port port :join? true})))