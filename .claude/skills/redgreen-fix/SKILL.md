---
name: redgreen-fix
description: Fix a defect in Penstock the evidentiary way — regression test first, shown failing with the exact production error, then the fix, then the full verification ladder. Use for any bug fix, CVE remediation, or behaviour change where "the new tests must fail on the old code".
---

# Red-green defect fix (Penstock)

Every defect fix in this repo lands with proof: the regression test demonstrably fails on the pre-fix code for the *right reason*, then passes. Follow this order — do not write the fix first.

## 0. Environment (this machine)

```bash
export JAVA_HOME=~/.sdkman/candidates/java/21.0.7-tem   # default JDK 25 breaks Gradle script compile
```

## 1. Red

1. Write the regression test before touching the defective code. Match house style: nested `@SpringBootTest` classes with `@TestPropertySource` (see `SecurityConfigTest`, `CorsPolicyTest`), scripted stub `LlmProvider` beans, `MockMvc` + `asyncDispatch` for SSE.
2. If the bug lives in threading, config, or startup, a stub-injected unit test cannot catch it — write an IT that crosses the real boundary (`IdentityPropagationIT` for the SSE executor; `MemoryModeStartupIT` for memory-mode contexts, which must replicate `main()`'s `spring.autoconfigure.exclude` list or they pass vacuously against H2).
3. Run it and **capture the failure reason**, not just the failure:
   ```bash
   ./gradlew --no-daemon test --tests <NewTest> 2>&1 | grep -E "FAILED|tests completed"
   # then confirm the cause in build/reports/tests/test/classes/<TestClass>.html
   ```
   A test failing for the wrong reason proves nothing.

## 2. Green

Apply the fix. Re-run the new test, then the full suite with totals:

```bash
./gradlew --no-daemon build
python3 -c "
import glob, xml.etree.ElementTree as ET
t=f=e=0
for x in glob.glob('build/test-results/test/TEST-*.xml'):
    r=ET.parse(x).getroot(); t+=int(r.get('tests')); f+=int(r.get('failures')); e+=int(r.get('errors'))
print(f'TOTAL: {t} tests, {f} failures, {e} errors')"
```

## 3. Real-world ladder (pick rungs the change can reach)

- Storage/config/startup changes: real `bootRun` in **memory AND sqlite** modes (sqlite exercises live Flyway + the SQLite dialect, which the H2 suite structurally cannot):
  ```bash
  AGENT_STORAGE_TYPE=memory AGENT_WORKSPACE=$SCRATCH/ws SERVER_PORT=18xxx timeout 75 ./gradlew --no-daemon bootRun 2>&1 | grep -m1 -E "Started AgentApplication|APPLICATION FAILED"
  ```
- Anything touching the image, dependencies, or entrypoint: run `/ship-check`.

## 4. Land

Commit with the red-run evidence in the message (what failed, why, on what). PR body states: the defect mechanism, why existing tests missed it, the before/after proof, and the test totals. If the old code's failure was demonstrated by temporarily checking out the pre-fix tree (`git checkout <base> -- src/...`), restore with `git checkout HEAD -- .` and say so in the PR.
