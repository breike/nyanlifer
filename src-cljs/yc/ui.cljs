(ns yc.ui
  "SPA: заметки + события + теги поверх nyanlifer API."
  (:require [reagent.core :as r]
            [cljs.core.async :refer [go <!]]
            [cljs-http.client :as http]))

;; ---------- state ----------

(defonce state (r/atom {:token nil            ;; Bearer-токен
                        :tab :notes           ;; :notes | :events
                        :tags []              ;; список тегов
                        :notes []             ;; заметки
                        :events []            ;; события
                        :filter-tag nil       ;; выбранный тег
                        :q ""                 ;; поиск
                        :login "" :password ""
                        :error nil
                        :note-form {:title "" :body "" :tags "" :event_id ""}
                        :event-form {:summary "" :start "" :duration 60 :desc "" :tags ""}}))

(defn auth-hdr []
  {:Authorization (str "Bearer " (:token @state))})

;; ---------- API helpers ----------

(defn api-get [path params]
  (http/get path {:headers (auth-hdr) :query-params (or params {}) :with-credentials? false}))

(defn api-post [path body]
  (http/post path {:headers (auth-hdr) :json-params body :with-credentials? false}))

(defn api-put [path body]
  (http/put path {:headers (auth-hdr) :json-params body :with-credentials? false}))

(defn api-delete [path]
  (http/delete path {:headers (auth-hdr) :with-credentials? false}))

(declare refresh!)

(defn split-tags [s]
  (->> (clojure.string/split (or s "") #"[,\s]+")
       (remove clojure.string/blank?)
       distinct))

(defn refresh []
  (go
    (let [tag (:filter-tag @state)
          q (:q @state)
          nresp (<! (api-get "/notes" (cond-> {} q (assoc :q q) tag (assoc :tag tag))))
          eresp (<! (api-get "/events" (cond-> {} q (assoc :q q) tag (assoc :tag tag))))
          tresp (<! (api-get "/tags" nil))]
      (swap! state assoc
             :notes (:body nresp)
             :events (:body eresp)
             :tags (get-in tresp [:body :tags])))))

;; ---------- login ----------

(defn login! []
  (go
    (let [resp (<! (http/post "/login" {:json-params {:login (:login @state)
                                                      :password (:password @state)}
                                        :with-credentials? false}))]
      (if (= 200 (:status resp))
        (do (swap! state assoc :token (get-in resp [:body :token]) :error nil)
            (refresh!))
        (swap! state assoc :error "Неверный логин или пароль")))))

(defn register! []
  (go
    (let [resp (<! (http/post "/register" {:json-params {:login (:login @state)
                                                         :password (:password @state)}
                                           :with-credentials? false}))]
      (if (<= 200 (:status resp) 299)
        (login!)
        (swap! state assoc :error "Не удалось зарегистрироваться (логин занят?)")))))

;; ---------- actions ----------

(defn create-note! []
  (let [f (:note-form @state)]
    (go
      (let [resp (<! (api-post "/notes" {:title (:title f) :body (:body f)
                                         :tags (split-tags (:tags f))
                                         :event_id (when-not (clojure.string/blank? (:event_id f))
                                                     (:event_id f))}))]
        (swap! state assoc :note-form {:title "" :body "" :tags "" :event_id ""})
        (refresh!)))))

(defn create-event! []
  (let [f (:event-form @state)]
    (go
      (let [resp (<! (api-post "/events" {:summary (:summary f) :start (:start f)
                                          :duration (:duration f) :desc (:desc f)
                                          :tags (split-tags (:tags f))}))]
        (swap! state assoc :event-form {:summary "" :start "" :duration 60 :desc "" :tags ""})
        (refresh!)))))

(defn delete-note! [id]
  (go (<! (api-delete (str "/notes/" id))) (refresh!)))

(defn delete-event! [uid]
  (go (<! (api-delete (str "/events/" uid))) (refresh!)))

(defn set-filter-tag [tag]
  (swap! state assoc :filter-tag tag)
  (refresh!))

;; ---------- components ----------

(defn login-view []
  [:div.login-box
   [:h1 "nyanlifer"]
   (when (:error @state) [:div.error (:error @state)])
   [:div.card
    [:input {:placeholder "Логин" :value (:login @state)
             :on-change #(swap! state assoc :login (-> % .-target .-value))}]
    [:input {:type "password" :placeholder "Пароль приложения" :value (:password @state)
             :on-change #(swap! state assoc :password (-> % .-target .-value))}]
    [:button.primary {:on-click login!} "Войти"]
    [:button {:on-click register! :style {:marginLeft 8}} "Регистрация"]]])

(defn tag-badge [tag]
  [:span.tag tag])

(defn note-card [n]
  [:div.card
   [:strong (:title n)]
   (when (:event_id n) [:div.muted "Событие: " (:event_id n)])
   [:div (:body n)]
   [:div (map tag-badge (:tags n))]
   [:button.danger {:on-click #(delete-note! (:id n))} "Удалить"]])

(defn event-card [e]
  [:div.card
   [:strong (:summary e)]
   [:div.muted (str (:start e) " · " (:duration e))]
   [:div (map tag-badge (:tags e))]
   (when (seq (:notes e))
     [:div.muted (str "Связанные заметки: " (count (:notes e)))])
   [:button.danger {:on-click #(delete-event! (:uid e))} "Удалить"]])

(defn tags-filter []
  [:div.filter
   [:strong "Тег: "]
   [:select {:value (or (:filter-tag @state) "")
             :on-change #(set-filter-tag (let [v (-> % .-target .-value)]
                                           (when-not (clojure.string/blank? v) v)))}
    [:option {:value ""} "Все"]
    (map (fn [t] ^{:key (:id t)} [:option {:value (:name t)} (:name t)]) (:tags @state))]
   [:input {:placeholder "Поиск..." :value (:q @state)
            :on-change #(swap! state assoc :q (-> % .-target .-value))
            :on-key-down #(when (= (-> % .-key) "Enter") (refresh!))}]
   [:button.primary {:on-click refresh!} "🔍"]])

(defn notes-view []
  [:div
   [tags-filter]
   [:div.card
    [:strong "Новая заметка"]
    [:input {:placeholder "Заголовок" :value (get-in @state [:note-form :title])
             :on-change #(swap! state assoc-in [:note-form :title] (-> % .-target .-value))}]
    [:textarea {:placeholder "Текст (можно с тегами в поле ниже)" :value (get-in @state [:note-form :body])
                :on-change #(swap! state assoc-in [:note-form :body] (-> % .-target .-value))}]
    [:div.two-col
     [:input {:placeholder "Теги через запятую" :value (get-in @state [:note-form :tags])
              :on-change #(swap! state assoc-in [:note-form :tags] (-> % .-target .-value))}]
     [:input {:placeholder "event_id (UID события, опц.)" :value (get-in @state [:note-form :event_id])
              :on-change #(swap! state assoc-in [:note-form :event_id] (-> % .-target .-value))}]]
    [:button.primary {:on-click create-note!} "Создать заметку"]]
   (map (fn [n] ^{:key (:id n)} [note-card n]) (:notes @state))])

(defn events-view []
  [:div
   [tags-filter]
   [:div.card
    [:strong "Новое событие"]
    [:input {:placeholder "Название" :value (get-in @state [:event-form :summary])
             :on-change #(swap! state assoc-in [:event-form :summary] (-> % .-target .-value))}]
    [:div.two-col
     [:input {:placeholder "ГГГГ-ММ-ДД [ЧЧ:ММ]" :value (get-in @state [:event-form :start])
              :on-change #(swap! state assoc-in [:event-form :start] (-> % .-target .-value))}]
     [:input {:type "number" :placeholder "Длительность (мин)" :value (get-in @state [:event-form :duration])
              :on-change #(swap! state assoc-in [:event-form :duration] (js/parseInt (-> % .-target .-value) 10))}]]
    [:input {:placeholder "Описание" :value (get-in @state [:event-form :desc])
             :on-change #(swap! state assoc-in [:event-form :desc] (-> % .-target .-value))}]
    [:input {:placeholder "Теги через запятую" :value (get-in @state [:event-form :tags])
             :on-change #(swap! state assoc-in [:event-form :tags] (-> % .-target .-value))}]
    [:button.primary {:on-click create-event!} "Создать событие"]]
   (map (fn [e] ^{:key (:uid e)} [event-card e]) (:events @state))])

(defn main-view []
  [:div.container
   [:div.nav
    [:h1 "nyanlifer"]
    [:button {:on-click #(swap! state assoc :token nil)} "Выйти"]]
   [:div.tabs
    [:button {:class (when (= (:tab @state) :notes) "active")
              :on-click #(swap! state assoc :tab :notes)} "Заметки"]
    [:button {:class (when (= (:tab @state) :events) "active")
              :on-click #(swap! state assoc :tab :events)} "События"]]
   (if (= (:tab @state) :notes) [notes-view] [events-view])])

(defn root []
  (if (:token @state)
    [main-view]
    [login-view]))

(defn refresh! [] (refresh))

(defn ^:export main []
  (r/render [root] (.getElementById js/document "app")))