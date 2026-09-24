# Policy Impact Engine — backend image (Phase 13-E, see DECISIONS.md ADR-028).
#
# Multi-stage: Maven + Java 17 build, slim JRE runtime. No source,
# build cache, secrets, or build args leak into the runtime layer.
# Build context is backend/ (run from the repository root):
#   docker build -f backend/Dockerfile backend/

# --- Build stage: compile + package (tests run in CI, not here) ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn/ .mvn/
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package

# --- Runtime stage: JRE only, non-root, read-only FS ---
FROM eclipse-temurin:17-jre-jammy
LABEL org.opencontainers.image.title="Policy Impact Engine"
LABEL org.opencontainers.image.description="Policy change impact analysis API (Phase 13-E local deployment)"

# curl (unpinned jammy-archive version) serves the HEALTHCHECK probe only.
RUN apt-get update \
	&& apt-get install -y --no-install-recommends curl \
	&& rm -rf /var/lib/apt/lists/*

# Fixed numeric UID so the runtime identity is stable across hosts.
RUN useradd --system --uid 65532 --no-create-home --shell /usr/sbin/nologin appuser
WORKDIR /app
COPY --from=build --chown=65532:65532 /src/target/policy-impact-engine-*.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
USER 65532

# Authenticated Actuator stays locked (see ADR-028 §4): 401 proves the
# server/security chain is up and locked, 200 is accepted as well.
# No credentials are ever sent; nothing here weakens authentication.
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
	CMD sh -c 'code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 4 http://127.0.0.1:8080/actuator/health); test "$code" = "401" -o "$code" = "200"'

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
