# nyanlifer — Containerfile (podman build / docker build)
#
# Многоступенчатая сборка:
#   1) build:  собирает CLJS SPA (node + shadow-cljs) и uberjar (lein)
#   2) runtime: JRE + standalone jar; данные/статика только из jar
#
# Запуск (пример):
#   podman build -t nyanlifer .
#   podman run -p 8000:8000 \
#     -e YANDEX_CALDAV_LOGIN=user@yandex-team.ru \
#     -e YANDEX_CALDAV_PASSWORD='xxxx-xxxx-xxxx' \
#     -e YC_DB_PATH=/data/db.sqlite \
#     -v yc-data:/data \
#     nyanlifer
#
# Креды CalDAV задаются ТОЛЬКО через env (пароль приложения языкового
# календаря) — в образ не попадают.

# ---------- стадия сборки ----------
FROM docker.io/library/node:22-slim AS build

# Node для CLJS
WORKDIR /app

# 1) UI-зависимости (layer cached отдельно)
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

# 2) Clojure: JRE + lein (в build-стадии нужен для uberjar)
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless curl \
    && rm -rf /var/lib/apt/lists/*
ENV LEIN_ROOT=1
RUN curl -sSL https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein \
    -o /usr/local/bin/lein && chmod +x /usr/local/bin/lein \
    # леин скачает свои зависимости в этом слое (потом переиспользуется)
    && lein version >/dev/null 2>&1 || true

# 3) исходники + конфиги
COPY project.clj shadow-cljs.edn ./
COPY src ./src
COPY src-cljs ./src-cljs
COPY resources ./resources
COPY test ./test

# 4) собрать CLJS SPA
RUN npx shadow-cljs compile app

# 5) собрать uberjar (внутри уже есть resources/public/js/main.js)
RUN lein uberjar

# ---------- runtime ----------
FROM docker.io/library/eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="nyanlifer" \
      org.opencontainers.image.description="Yandex Calendar CalDAV + notes + tags (Clojure)" \
      org.opencontainers.image.source="https://github.com/your/nyanlifer"

WORKDIR /app

# Пользователь без прав; данные — в volume
RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /data && chown app:app /data
VOLUME ["/data"]

COPY --from=build /app/target/nyanlifer-0.1.0-SNAPSHOT-standalone.jar /app/app.jar

ENV YC_PORT=8000 \
    YC_DB_PATH=/data/db.sqlite \
    AUTH_TOKEN_TTL=86400

EXPOSE 8000

USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]