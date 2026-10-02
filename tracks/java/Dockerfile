# syntax=docker/dockerfile:1
# Build context = repository root (so the shared spec/lua scripts can be copied):
#   docker build -f tracks/java/Dockerfile -t rateguard-java .

# ---- build stage: Maven + JDK, dependencies cached in their own layer ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY tracks/java/pom.xml tracks/java/checkstyle.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY tracks/java/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests -Dcheckstyle.skip -Djacoco.skip=true \
    && java -Djarmode=tools -jar target/app.jar extract --layers --launcher --destination /extracted

# ---- runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre
ENV PORT=8080 LUA_DIR=/app/lua \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app \
    && apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
COPY spec/lua ./lua
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/health > /dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
