# Деплой nyanlifer на Linux-сервер (systemd, без reverse-proxy)

Инструкция для продакшн-сервера: приложение слушает HTTP-порт **напрямую**
(без TLS/nginx/Caddy). TLS/домен — на усмотрение (см. раздел «TLS» в конце).

Проверено: сборка `lein uberjar` → `java -jar`, health за ~4 сек (прогрев JVM).

---

## 0. Требования

| Компонент | Версия | Примечание |
|---|---|---|
| JVM | 11+ | проверено на Java 25 (Temurin), в образе — 17 |
| Leiningen | 2.x | только для сборки на билд-машине |
| systemd | (любая) | юнит `deploy/nyanlifer.service` |
| Креды Яндекса | — | пароль приложения (CalDAV), опционально |

Порты: приложение слушает `8000` (`YC_PORT`). **Порт должен быть свободен** и,
если сервер смотрит в интернет, — закрыт файрволом наружу (см. §5).

---

## 1. Сборка jar

Сборку можно делать где угодно (у себя или CI); на сервере нужна только JVM.

```bash
cd ~/Stefania/local/yandex-calendar-clj

# собрать CLJS SPA (её файлы попадают в jar из resources/)
npx shadow-cljs compile app      # нужен node + npm install

# собрать standalone-банку
lein uberjar

# результат:
#   target/nyanlifer-0.1.0-SNAPSHOT-standalone.jar
```

> Собрать jar **без** node (без UI): `lein uberjar` сам по себе; статика
> `/js/main.js` тогда из resources/public/js — если она уже была собрана ранее
> и лежит в resources/. Для чистоты — сначала команда shadow-cljs выше.

---

## 2. Раскладка на сервере

Рекомендуемая структура:

```
/opt/nyanlifer/
  nyanlifer.jar        # ↔ target/nyanlifer-0.1.0-SNAPSHOT-standalone.jar
  DEPLOY.md            # эта инструкция (для удобства)
/var/lib/nyanlifer/
  db.sqlite            # SQLite-БД (создаётся автоматически при первом запуске)
/etc/nyanlifer/
  env                  # (опц.) креды Яндекса, chmod 600
```

Создать пользователя и каталоги:

```bash
sudo useradd --system --home-dir /var/lib/nyanlifer --shell /usr/sbin/nologin \
     nyanlifer
sudo install -d -o nyanlifer -g nyanlifer /var/lib/nyanlifer
sudo install -d -o root -g root -m 0755 /opt/nyanlifer /etc/nyanlifer
```

Скопировать jar:

```bash
sudo install -o root -g root -m 0644 \
  target/nyanlifer-0.1.0-SNAPSHOT-standalone.jar /opt/nyanlifer/nyanlifer.jar
sudo cp DEPLOY.md deploy/nyanlifer.service /opt/nyanlifer/ 2>/dev/null || true
```

---

## 3. Креды Яндекса (опционально)

Без кредов приложение полностью работает локально (календарь самодостаточен),
Яндекс-синхронизация просто неактивна. Если нужна — создай файл:

```bash
sudo install -o root -g root -m 0600 /dev/null /etc/nyanlifer/env
sudoedit /etc/nyanlifer/env
```

Содержимое (одна строка на переменную):

```
YANDEX_CALDAV_LOGIN=user@yandex.ru
YANDEX_CALDAV_PASSWORD=xxxx-xxxx-xxxx-xxxx     # пароль ПРИЛОЖЕНИЯ, не основной
```

Юнит читает его как `EnvironmentFile=-/etc/nyanlifer/env` (минус перед путём =
файл необязателен). Пароль **не** держи в git/README — только этот файл.

> Прочие переменные уже захардкожены в юните: `YC_PORT=8000`,
> `YC_DB_PATH=/var/lib/nyanlifer/db.sqlite`, `AUTH_TOKEN_TTL=86400`.

---

## 4. systemd

Установить юнит (путь для Debian/Ubuntu; на Arch — `/etc/systemd/system`,
на Fedora/RHEL — `/etc/systemd/system`):

```bash
sudo install -o root -g root -m 0644 deploy/nyanlifer.service \
  /etc/systemd/system/nyanlifer.service
sudo systemctl daemon-reload
sudo systemctl enable --now nyanlifer
```

**Проверка**

```bash
# статус (должен быть active (running))
systemctl status nyanlifer

# health-эндпоинт
curl -s http://127.0.0.1:8000/health        # → {"status":"ok"}
curl -s http://127.0.0.1:8000/              # → SPA index.html

# логи (journald)
journalctl -u nyanlifer -n 50 -f
```

При первом старте создаётся `/var/lib/nyanlifer/db.sqlite` (схема
инициализируется автоматически — файл не нужно создавать заранее).

**Управление**

```bash
sudo systemctl restart nyanlifer    # перезапуск
sudo systemctl stop nyanlifer       # остановка
```

---

## 5. Файрвол / экспонирование

Юнит **по умолчанию открыт для локальной сети**: `PrivateNetwork`/`IPAddressAllow`
в нём нет, Jetty слушает `*:8000` (все интерфейсы). LAN-хосты (например,
`192.168.0.x`) имеют доступ сразу после установки — достаточно, чтобы системный
файрвол не блокировал порт 8000.

Если нужно **ограничить только loopback** (слушать лишь `127.0.0.1`), добавь в
`[Service]` обе строки:

```ini
PrivateNetwork=true
IPAddressAllow=127.0.0.1
```

> ⚠️ Добавлять **только** `PrivateNetwork` недостаточно: фильтр
> `IPAddressAllow=127.0.0.1` нужен обязательно, иначе cgroup останется открыт.
> Добавляй **обе** строки вместе — и `daemon-reload` + `restart`.

Доступ извне (через интернет) — два пути:

**a) Оставить loopback, проброс на ТСП/шифр через ssh:**

```bash
# на клиентской машине (локальный порт 8000 → сервер:8000 по ssh-туннелю)
ssh -L 8000:127.0.0.1:8000 user@server
# потом открыть http://127.0.0.1:8000 (или настроить связку nginx+certbot на сервере)
```

**b) Открыть порт наружу — без loopback-ограничения:**

Убедись, что в юните НЕТ обеих строк выше, затем открой порт в системном
файрволе (примеры):

```bash
# nftables
sudo nft add rule inet filter input tcp dport 8000 accept
# ufw (Debian/Ubuntu)
sudo ufw allow 8000/tcp
# firewalld (Fedora/RHEL)
sudo firewall-cmd --permanent --add-port=8000/tcp && sudo firewall-cmd --reload
```

> **Важно:** открывать наружу без TLS не рекомендуется; в интернете — только
> через reverse-proxy с TLS (раздел «TLS»), либо ssh-туннель (а).

---

## 6. Обновление приложения

```bash
# 1) на билд-машине пересобрать (см. §1)
npx shadow-cljs compile app && lein uberjar

# 2) залить восстановленный jar на сервер
scp target/nyanlifer-0.1.0-SNAPSHOT-standalone.jar user@server:/tmp/nyanlifer.jar

# 3) на сервере: остановить, заменить, запустить
sudo systemctl stop nyanlifer
sudo install -o root -g root -m 0644 /tmp/nyanlifer.jar /opt/nyanlifer/nyanlifer.jar
sudo systemctl start nyanlifer

# 4) проверить
systemctl status nyanlifer && curl -s http://127.0.0.1:8000/health
```

БД не трогается (лежит отдельно в `/var/lib/nyanlifer`); схема и данные
совместимы между версиями (миграций пока нет).

---

## 7. Бэкапы

Данные — в одном SQLite-файле. Бэкап через `sqlite3 .backup` (безопасно
на лету, без остановки сервиса):

```bash
# простой бэкап
sqlite3 /var/lib/nyanlifer/db.sqlite ".backup '/var/backups/nyanlifer-$(date +%F).sqlite'"

# ежедневно через systemd-timer (пример: /etc/systemd/system/nyanlifer-backup.{service,timer})
```

`nyanlifer-backup.service`:

```ini
[Unit]
Description=Backup nyanlifer SQLite DB

[Service]
Type=oneshot
ExecStart=/usr/bin/sqlite3 /var/lib/nyanlifer/db.sqlite ".backup /var/backups/nyanlifer-%F.sqlite"
```

`nyanlifer-backup.timer`:

```ini
[Unit]
Description=Daily nyanlifer backup

[Timer]
OnCalendar=03:17                # днём позже полуночи
Persistent=true

[Install]
WantedBy=timers.target
```

Активация:

```bash
sudo systemctl enable --now nyanlifer-backup.timer
sudo mkdir -p /var/backups
```

Восстановление (на остановленном сервисе):

```bash
sudo systemctl stop nyanlifer
sudo install -o nyanlifer -g nyanlifer -m 0644 \
  /var/backups/nyanlifer-2026-11-08.sqlite /var/lib/nyanlifer/db.sqlite
sudo systemctl start nyanlifer
```

> Держи бэкапы на другом диске/хосте; проверяй восстановление.

---

## 8. Мониторинг / диагностика

```bash
# всегда ли жив сервис
systemctl is-active nyanlifer

# последние ошибки
journalctl -u nyanlifer -p err -n 20

# health
curl -fsS http://127.0.0.1:8000/health

# размер БД
du -h /var/lib/nyanlifer/db.sqlite
```

Полезно добавить внешний check (cron/systemd-timer), дёргающий `/health`:

```bash
# добавить в cron (на сервере)
*/5 * * * * curl -fsS http://127.0.0.1:8000/health >/dev/null || systemctl restart nyanlifer
```

---

## 9. Диагностика частых проблем

| Симптом | Причина / решение |
|---|---|
| `systemctl status` → `failed (code=exited, status=203/EXEC)` | нет `/usr/bin/java` у пользователя: `which java; java -version`; проверь `ExecStart` |
| Health 500 при создании события | креды заданы неверно (см. §3) / CalDAV недоступен |
| Порт 8000 занят: `Address already in use` | другой процесс: `ss -ltnp | grep 8000`; смени `YC_PORT` |
| «505: Database is locked» | два процесса на одной БД; убедись, что юнит один, `busy_timeout` уже 5с |
| Сеть не слушается снаружи | юнит ограничен loopback (PrivateNetwork+IPAddressAllow) — см. §5 |
| `systemctl enable` ругается | юнит уже active; `daemon-reload` сначала |

---

## 10. TLS (по желанию)

Без прокси TLS-терминации нет. Варианты, если нужен HTTPS наружу:

1. **nginx + certbot** (рекомендуется): слушай 443, проксируй на 127.0.0.1:8000,
   `certbot --nginx -d ваш-домен`. Для этого добавь в юнит loopback-ограничение
   (см. §5): `PrivateNetwork=true` + `IPAddressAllow=127.0.0.1` — nginx на том же
   хосте ходит по 127.0.0.1, это разрешено.
2. **Caddy**: `reverse_proxy 127.0.0.1:8000` + авто-TLS.

В обоих случаях порт слушается на loopback, наружу ходит только прокси.

---

## Переменные окружения (сводка)

| Переменная | По умолчанию | Где задана |
|---|---|---|
| `YC_PORT` | `8000` | в юните |
| `YC_DB_PATH` | `data/db.sqlite` | в юните → `/var/lib/nyanlifer/db.sqlite` |
| `AUTH_TOKEN_TTL` | `86400` | в юните |
| `YANDEX_CALDAV_SERVER` | `https://caldav.yandex.ru` | в юните |
| `YANDEX_CALDAV_LOGIN` | — | `/etc/nyanlifer/env` |
| `YANDEX_CALDAV_PASSWORD` | — | `/etc/nyanlifer/env` |