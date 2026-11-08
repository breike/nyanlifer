# nyanlifer

Единое Clojure-приложение: **календарь событий + заметки + система тегов,**
объединяющая события и заметки. Переписанный на Clojure аналог
`../yandex-calendar-web` (Python: 4 микросервиса + shared/caldav_client.py).

В отличие от Python-версии — это **один процесс** (JVM + Jetty), одна SQLite-БД,
без отдельного auth/calendar/notes сервисов. Функциональность + система тегов
(новое).

**Календарь самодостаточен** (`yc.calendar`: события, теги, поиск, связь с
заметками хранятся локально в SQLite). **Яндекс.Календарь — лишь модуль
интеграции** (`yc.integrations.yandex`): синхронизирует локальные события с
CalDAV-сервером по опциональным кредам. Без кредов события полноценно
создаются/списком/фильтруются локально (`POST /events` → 201).

## Возможности

- **Заметки** — CRUD, полнотекстовый поиск (`?q=`), связь с событием (`event_id`).
- **События** — создаются локально в SQLite (ядро календаря, без внешних
  зависимостей); при заданных кредах дублируются в Яндекс.Календарь
  (CalDAV PROPFIND/PUT, WebDAV) опционально.
- **Теги** — единая система для заметок и событий: фильтр `?tag=` объединяет
  оба типа (например, тег `проект-X` показывает и заметки, и события проекта).
- **Связь заметка ↔ событие**:
  - из события видно связанные заметки (`GET /events/{uid}/notes`)
  - из заметки виден `event_id` (и наоборот).
- **Auth** — регистрация, login, Bearer-токены (PBKDF2 для паролей, SHA-256 хеш
  токенов, TTL), logout, verify — как в Python auth-service.

## Стек

| Компонент | Технология |
|---|---|
| Язык | Clojure 1.12 (JVM) |
| HTTP | ring + compojure + Jetty (один процесс) |
| JSON | cheshire (запрос), ring-json (ответ) |
| SQLite | sqlite-jdbc (чистый JDBC, пул соединений на запрос) |
| CalDAV | java.net.http (HttpClient поддерживает WebDAV PROPFIND/PUT) |
| Тесты | clojure.test + ring-mock |

## Требования

- JVM 11+ (проверено на Java 25)
- Leiningen 2.x (`lein`)
- Учётные данные Яндекс.Календаря (пароль приложения), см. ниже

## Быстрый старт

```bash
# 1) зависимости (первый раз скачает из Maven Central / Clojars)
lein deps

# 2) конфиг учётных данных (по желанию) — ~/.config/yandex-caldav:
#    login = user@yandex-team.ru
#    password = xxxx-xxxx-xxxx-xxxx     # пароль приложения
#    server = https://caldav.yandex.ru  # опционально

# 3) запуск
lein run                       # порт по умолчанию 8000
# или с настройками:
YC_PORT=8000 YC_DB_PATH=data/db.sqlite \
YANDEX_CALDAV_LOGIN=... YANDEX_CALDAV_PASSWORD=... \
lein run
```

Откройте http://127.0.0.1:8000 → `/health`, затем `POST /register`, `POST /login`,
используйте Bearer-токен.

Переменные окружения:

| Переменная | По умолчанию | Описание |
|---|---|---|
| `YC_PORT` | `8000` | HTTP-порт |
| `YC_DB_PATH` | `data/db.sqlite` | путь к SQLite-файлу |
| `AUTH_TOKEN_TTL` | `86400` | TTL токена (сек) |
| `YANDEX_CALDAV_LOGIN` | — | логин CalDAV (или из ~/.config) |
| `YANDEX_CALDAV_PASSWORD` | — | пароль приложения CalDAV |
| `YANDEX_CALDAV_SERVER` | `https://caldav.yandex.ru` | сервер CalDAV |

## API

Все эндпоинты, кроме `/register` `/login` `/health`, требуют заголовок
`Authorization: Bearer <token>`.

### Auth

| Метод | Путь | Описание |
|---|---|---|
| POST | `/register` | `{login, password}` → 201 `{login}` или 409 |
| POST | `/login` | `{login, password}` → 200 `{token, expires_at}` |
| POST | `/logout` | отозвать токен |
| POST | `/verify` | проверить токен → `{user_id, login}` |
| GET | `/health` | `{status: "ok"}` |

### Заметки

| Метод | Путь | Описание |
|---|---|---|
| GET | `/notes?q=&tag=` | список/поиск; `tag` фильтрует по тегу |
| POST | `/notes` | `{title, body, event_id?, tags?: [..]}` → 201 заметка |
| GET | `/notes/{id}` | одна заметка (с `tags`) |
| PUT | `/notes/{id}` | частичное обновление `{title?, body?, event_id?, tags?}` |
| DELETE | `/notes/{id}` | удалить |

Ответ заметки: `{id, title, body, event_id, tags, created_at, updated_at}`.

### События (календарь)

| Метод | Путь | Описание |
|---|---|---|
| GET | `/calendars` | список календарей (CalDAV PROPFIND; нужны креды Яндекса) |
| POST | `/events` | создать событие: `{summary, start, duration?, desc?, loc?, calendar_href?, tags?}` |
| GET | `/events?q=&tag=` | события (фильтр по тегу/поиск) |
| GET | `/events/{uid}` | событие + `{tags, notes: [id..]}` (связанные заметки) |
| GET | `/events/{uid}/notes` | связанные заметки (обратная связь) |
| DELETE | `/events/{uid}` | удалить событие (яндексовский .ics — best-effort) |

`start` — `ГГГГ-ММ-ДД` (весь день) или `ГГГГ-ММ-ДД ЧЧ:ММ`.

Событие всегда создаётся локально. Если заданы `YANDEX_CALDAV_LOGIN/PASSWORD`,
оно также пушится в Яндекс.Календарь (и запись получает `url`/`calendar_href`);
при неудачном пуше локальное событие откатывается.

### Теги

| Метод | Путь | Описание |
|---|---|---|
| GET | `/tags` | все теги пользователя |
| POST | `/tags` | `{name}` → создать тег (idempotent) |
| DELETE | `/tags/{id}` | удалить тег (отвязывает от заметок/событий) |

## Пример связки «тег объединяет заметку и событие»

```bash
TOKEN=$(curl -s -X POST localhost:8000/login -H 'Content-Type: application/json' \
  -d '{"login":"me@yandex-team.ru","password":"app-pass"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# Событие с тегом "проект"
curl -s -X POST localhost:8000/events -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"summary":"Созвон по проекту","start":"2026-12-01 12:00","duration":60,"tags":["проект"]}'
# → uid, и видно связанные notes: []

# Заметка с тем же тегом + привязкой к событию
curl -s -X POST localhost:8000/notes -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"План созвона","body":"...","event_id":"<uid>","tags":["проект"]}'
# → заметка с event_id, tags: ["проект"]

# Единый фильтр: и событие, и заметка
curl -s "localhost:8000/events?tag=проект" -H "Authorization: Bearer $TOKEN"  # → событие
curl -s "localhost:8000/notes?tag=проект"  -H "Authorization: Bearer $TOKEN"  # → заметка

# Обратная связь: событие → заметки / заметка → событие
curl -s localhost:8000/events/<uid>         -H "Authorization: Bearer $TOKEN"  # notes: [id]
curl -s localhost:8000/events/<uid>/notes   -H "Authorization: Bearer $TOKEN"  # сама заметка
```

## UI (ClojureScript SPA)

Одностраничное приложение на **Reagent** (CLJS) поверх этого API — без отдельного
сервера, статика раздаётся тем же Jetty из `resources/public`.

- Вкладки **Заметки** / **События**, единый фильтр по тегу и поиск.
- Создание заметок (с тегами и `event_id`) и событий (с тегами).
- Связь: у события видны связанные заметки; тег объединяет оба раздела.
- Логин/регистрация, выход.

Сборка UI:

```bash
npm install                  # один раз (shadow-cljs + react)
npx shadow-cljs compile app  # собрать resources/public/js/main.js
lein run                     # затем обычный запуск сервера
# dev-режим с hot reload:
npx shadow-cljs watch app
```

Структура UI: `src-cljs/yc/ui.cljs`, конфиг `shadow-cljs.edn`,
`resources/public/index.html`.

## Деплой

Пошаговое развёртывание на продакшн-сервере (systemd, без reverse-proxy):
см. **[DEPLOY.md](DEPLOY.md)** — сборка jar, установка, юнит
`deploy/nyanlifer.service`, креды, обновление, бэкапы, мониторинг, TLS (опц.).

## Контейнер (Podman/Docker)

Многоступенчатый `Containerfile`: сборка (node 22 + shadow-cljs → CLJS,
lein → uberjar) и runtime (`eclipse-temurin:17-jre-alpine`, непривилегированный
пользователь, данные в volume `/data`).

```bash
# сборка
podman build -t nyanlifer .

# запуск (порт 8000, данные — volume, креды — env)
podman run -d --name yc \
  -p 8000:8000 \
  -e YANDEX_CALDAV_LOGIN='user@yandex-team.ru' \
  -e YANDEX_CALDAV_PASSWORD='xxxx-xxxx-xxxx-xxxx' \
  -v yc-data:/data \
  nyanlifer

# проверить
curl -s localhost:8000/health    # {"status":"ok"}
curl -s localhost:8000/          # SPA index.html
```

- **Креды CalDAV** задаются только через env при запуске; в образ не попадают.
- **UI** собран внутри образа (`/js/main.js`), раздаётся из jar.
- Docker: `docker build -t nyanlifer .` — тот же Containerfile.

## Тесты

```bash
lein run -m yc.test-runner    # 12 тестов, 63 assertion, автономные (auth/tags/notes/calendar/API)
```

Бэкенд также собирается в standalone-банку: `lein uberjar` → `java -jar
target/nyanlifer-0.1.0-SNAPSHOT-standalone.jar` (нужны те же env).

Тесты используют изолированную БД `data/test.db` (удаляется перед прогоном),
не трогают `data/db.sqlite` и не ходят в Яндекс (CalDAV проверяется
интеграционно, вручную).

## Структура

```
src/yc/
  core.clj      — точка входа (Jetty, конфиг)
  db.clj        — SQLite на чистом JDBC, схема, хелперы
  util.clj      — sha256/pbkdf2, base64, uuid, ICS-escape
  auth.clj      — register/login/logout/verify, токены
  tags.clj      — теги: CRUD, привязка к заметкам/событиям
  notes.clj     — заметки: CRUD + event_id + поиск + теги
  calendar.clj  — ядро календаря: события в SQLite, теги, поиск, связь с заметками (самодостаточно)
  integrations/
    yandex.clj  — модуль интеграции с Яндекс.Календарём (CalDAV PROPFIND/PUT/DELETE, ICS, креды)
  api.clj       — compojure-маршруты, JSON, error-handling (связывает ядро и интеграцию)
test/yc/        — clojure.test
```

## Сравнение с Python-версией

| | yandex-calendar-web (Python) | nyanlifer (Clojure) |
|---|---|---|
| Процессы | 4 сервиса (auth 8100, calendar 8200, notes 8300, ui 8000) | 1 процесс (Jetty) |
| БД | 3 SQLite-файла | 1 SQLite-файл |
| Теги | нет | **есть** (note_tags, event_tags) |
| Связь заметка↔событие | только `event_id` у заметки | `event_id` + обратная `/events/{uid}/notes` |
| CalDAV | urllib (стандартная библиотека), события нельзя без него | java.net.http (WebDAV PROPFIND/PUT/DELETE); календарь самодостаточен, Яндекс — модуль интеграции |
| UI | Jinja2 (Python) | ClojureScript (reagent) |

## Безопасность

Как в Python-версии: пароли хранятся PBKDF2 (salt$hex), токены — SHA-256 хеш
с TTL, логи без паролей. Учётка CalDAV — из env/конфига (общая для всех
пользователей; для продакшена — привязывать пароль приложения к пользователю
через защищённое хранилище).

NB: `nyanlifer` использует `~/bin/yandex-calendar-add` и
`shared/caldav_client.py` как эталон CalDAV-логики; порт сохраняет поведение:
principal → calendar-home-set → календари → PUT .ics.