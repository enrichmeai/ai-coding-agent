---
name: ship-check
description: Local distribution verification for Penstock — full build with the right JDK, BuildKit image build, as-shipped smoke test, both-storage-mode boots, optional Trivy scan. Use before cutting a release, after dependency/image/entrypoint changes, or when asked "does it still ship?".
---

# Ship check (Penstock)

Proves locally what the release pipeline will prove in CI — cheaper to fail here.

## 0. Environment (this machine)

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.7-tem   # default JDK 25 breaks Gradle 8 script compile
SCRATCH=<session scratchpad>                             # never /tmp, never the repo
```

Docker on this machine is 20.10: every image build needs `DOCKER_BUILDKIT=1` (the Dockerfile uses `$BUILDPLATFORM`, which the legacy builder can't parse).

## 1. Build + suite, with totals

```bash
./gradlew --no-daemon clean build
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for x in glob.glob('build/test-results/test/TEST-*.xml'):
    r=ET.parse(x).getroot(); t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors'))
print(f'TOTAL: {t} tests, {f} failures, {e} errors')"
```

Report the totals, not just BUILD SUCCESSFUL.

## 2. Real startup, both storage modes

The H2-based suite cannot see the real memory-mode context (no `main()` exclusions) or real SQLite (Flyway + dialect). Boot both:

```bash
AGENT_STORAGE_TYPE=memory AGENT_WORKSPACE=$SCRATCH/ws SERVER_PORT=18324 timeout 75 \
  ./gradlew --no-daemon bootRun 2>&1 | grep -m1 -E "Started AgentApplication|APPLICATION FAILED"
rm -f $SCRATCH/ship.db
AGENT_STORAGE_TYPE=sqlite AGENT_SQLITE_PATH=$SCRATCH/ship.db AGENT_WORKSPACE=$SCRATCH/ws SERVER_PORT=18325 timeout 90 \
  ./gradlew --no-daemon bootRun 2>&1 | grep -m1 -E "Started AgentApplication|APPLICATION FAILED|FlywayException"
```

Grep patterns must be strict — Flyway's benign "schema history table … does not exist yet" INFO line will false-match loose patterns.

## 3. Image: build + as-shipped smoke

```bash
DOCKER_BUILDKIT=1 docker build -t penstock:ship-check . > $SCRATCH/ship-build.log 2>&1; echo "exit=$?"
docker run -d --name ship-smoke -p 18326:8080 penstock:ship-check   # NO env overrides — as shipped
for i in $(seq 1 30); do curl -fsS http://localhost:18326/api/health >/dev/null 2>&1 && ok=1 && break; sleep 2; done
curl -fsS http://localhost:18326/actuator/info   # build.version must match expectations
[ "${ok:-}" = 1 ] && echo SMOKE-PASS || docker logs ship-smoke | tail -20
docker rm -f ship-smoke
```

No env overrides on the smoke run — the image must boot the way the README one-liner runs it.

## 4. Optional: Trivy (for dependency changes)

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest \
  image --severity HIGH,CRITICAL --scanners vuln penstock:ship-check > $SCRATCH/trivy.txt
grep "Total:" $SCRATCH/trivy.txt
```

Read the report's own `Total:` line — never count severity cells (grouped rows undercount). Decompose before reporting: `agent.jar` findings are runtime-reachable; `/opt/gradle-seed` and Gradle-distribution jars are toolchain tail.

## 5. Report

State every rung's result with its number (test totals, boot outcomes per mode, smoke result, `build.version`, scan total + decomposition). A rung you skipped, say you skipped.
