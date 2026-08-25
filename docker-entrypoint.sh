#!/usr/bin/env bash
# Seed GRADLE_USER_HOME on first start, then run the agent.
#
# The image ships /opt/gradle-seed: the Gradle wrapper distribution plus this
# project's resolved dependencies. Gradle needs to write into its home (locks,
# caches, daemon state), and the container runs read-only, so the seed is copied
# once into the writable volume mounted at GRADLE_USER_HOME rather than used
# where it lies. Without this the agent's first `./gradlew test` tries to
# download a Gradle distribution and fails on an offline host.
set -euo pipefail

GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/agent/.gradle}"

if [ -d /opt/gradle-seed ] && [ ! -e "${GRADLE_USER_HOME}/.seeded" ]; then
    echo "Seeding Gradle home at ${GRADLE_USER_HOME} (first start)..."
    if mkdir -p "${GRADLE_USER_HOME}" 2>/dev/null \
       && cp -a /opt/gradle-seed/. "${GRADLE_USER_HOME}/" 2>/dev/null; then
        touch "${GRADLE_USER_HOME}/.seeded"
        echo "Gradle home seeded."
    else
        # Not fatal: the agent still serves requests, it just cannot build.
        echo "WARN: could not seed ${GRADLE_USER_HOME}; builds inside the" \
             "workspace will fail. Is a writable volume mounted there?" >&2
    fi
fi

exec java ${JAVA_OPTS:-} -jar /app/agent.jar
