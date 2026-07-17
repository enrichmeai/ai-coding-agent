# Multi-stage build: Gradle 8.10 + JDK 21 to compile, then a slim JRE 21 runtime.

# ----- build stage -----
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace

# Copy build descriptors first to maximise layer caching
COPY settings.gradle build.gradle ./
COPY gradle ./gradle
COPY gradlew ./
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN gradle --no-daemon clean bootJar -x test

# ----- runtime stage -----
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends bash git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Non-root user
RUN useradd -ms /bin/bash agent
WORKDIR /app

COPY --from=build /workspace/build/libs/ai-coding-agent-*.jar /app/agent.jar

# Workspace + DB volume mount points
RUN mkdir -p /workspace /data && chown -R agent:agent /app /workspace /data
USER agent

ENV AGENT_WORKSPACE=/workspace \
    AGENT_SQLITE_PATH=/data/agent.db \
    JAVA_OPTS=""

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s \
    CMD curl -fsS http://localhost:8080/api/health > /dev/null || exit 1

# Recommended production run:
#   docker run --read-only --tmpfs /tmp --tmpfs /home/agent/.cache \
#              --cap-drop=ALL --pids-limit=256 --memory=1g \
#              -v $(pwd)/data:/data -v /path/to/workspace:/workspace \
#              -p 8080:8080 ai-coding-agent:latest

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/agent.jar"]
