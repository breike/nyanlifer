(defproject nyanlifer "0.1.0-SNAPSHOT"
  :description "Единый Clojure-бэкенд: Яндекс.Календарь (CalDAV) + заметки + теги"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [compojure "1.7.1"]
                 [ring/ring-core "1.12.2"]
                 [ring/ring-jetty-adapter "1.12.2"]
                 [ring/ring-json "0.5.1"]
                 [cheshire "5.13.0"]
                 [org.xerial/sqlite-jdbc "3.46.1.3"]
                 [org.clojure/java.jdbc "0.7.12"]]
  :source-paths ["src"]
  :test-paths ["test"]
  :main yc.core
  :profiles {:dev {:dependencies [[ring/ring-mock "0.4.0"]]}})