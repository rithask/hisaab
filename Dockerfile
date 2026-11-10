# syntax=docker/dockerfile:1
# Hisaab: one image with the React frontend served by the Spring Boot backend.

# The frontend bundle and the backend jar are the same on every CPU, so both are built once on the build machine's
# own platform ($BUILDPLATFORM), at full speed. Only the small runtime stage is assembled per target platform
# (amd64, arm64); building Node and Java under emulation would take many times longer.

# ---- 1. frontend ------------------------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM node:22-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. backend -------------------------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS api
WORKDIR /api
# Dependencies first so they're cached between builds (the cache mount keeps downloads between runs too).
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn .mvn
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY backend/src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package && cp target/*.jar /api/app.jar

# ---- 3. runtime -------------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre
# curl: health check. postgresql-client: pg_dump 18 for automatic backups; it can dump PostgreSQL 18 and
# anything older, so it must stay >= the db container's version.
RUN apt-get update && apt-get install -y --no-install-recommends curl postgresql-client \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --user-group --home /app hisaab \
    && mkdir -p /app/static /data /backups && chown -R hisaab:hisaab /app /data /backups
WORKDIR /app
COPY --from=api /api/app.jar /app/app.jar
COPY --from=web /web/dist /app/static
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh

ARG VERSION=dev
ENV PORT=8080 \
    APP_STATIC_DIR=/app/static \
    STORAGE_DIR=/data \
    BACKUP_DIR=/backups \
    APP_VERSION=${VERSION} \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
LABEL org.opencontainers.image.title="Hisaab" \
      org.opencontainers.image.version="${VERSION}"
VOLUME ["/data", "/backups"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD curl -fsS http://localhost:${PORT}/api/health || exit 1
# The entrypoint fixes folder ownership as root, then runs the app as the "hisaab" user.
ENTRYPOINT ["/app/entrypoint.sh"]
