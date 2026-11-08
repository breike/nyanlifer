(ns yc.markdown
  "Серверный рендер markdown в безопасный HTML.
  API хранит markdown в body, наружу отдаёт и html (body_html).
  Рендер — markdown-clj, санитайзинг — jsoup (Safelist, как OWASP).
  jsoup надёжно вырезает опасные теги/атрибуты/протоколы."
  (:require [markdown.core :as md])
  (:import (org.jsoup Jsoup)
           (org.jsoup.safety Safelist)))

(def ^:private safe-list
  ;; relaxed: a[href http/https/mailto], b, blockquote, br, caption, cite, code,
  ;; col/colgroup, dd/dl/dt, em, h1-h6, hr, i, img[src http/https], li, ol, p,
  ;; pre, q, s, small, span, strike, strong, sub, sup, table/tbody/td/tfoot/th/
  ;; thead/tr, u, ul. Протоколы javascript:/vbscript: jsoup вырезает сам
  ;; (не входят в allowlist), on*-атрибуты — тоже.
  (Safelist/relaxed))

(defn md->html
  "Рендер markdown-строки в безопасный HTML. Пустая строка -> ''.
  jsoup удаляет неразрешённые теги (script/iframe/...), опасные протоколы
  (javascript:) и on*-атрибуты.
  NOTE: Jsoup/clean без baseUri резолвит href/src относительно '/', поэтому
  относительные markdown-ссылки теряются — передаём абсолютный baseUri."
  [md-text]
  (when (seq (str md-text))
    (-> (md/md-to-html-string (str md-text)
                              :reference-links? true
                              :footnotes? true
                              :hard-wrap? false)
        (Jsoup/clean "https://nyanlifer.local/" safe-list)
        (clojure.string/trim))))