---
name: agent-validate
description: Drive Penstock against a real repository checkout and record what it actually does — turns used, tokens, whether the task completed, and what broke. Use when changing the tool layer or loop bounds, before claiming the agent "can" do something, or to add evidence to docs/real-repo-validation.md.
---

# Real-repo validation

Toy tasks in an empty directory prove nothing about this agent. Every significant
finding in `docs/real-repo-validation.md` came from driving it against actual
code, and each one contradicted a reasonable-sounding assumption:

- `list_dir` was single-level, so six of ten turns went on walking a package tree.
- The agent invented `@HeadMapping`, which Spring does not have, and could not
  discover that because it had no compiler.
- Given a corrupted test, it edited **production code** to make the test pass.

None of those show up in unit tests.

## Safety: never point it at the working tree

The agent edits what it is given. Always mount a throwaway clone.

```bash
V="$SCRATCH/val-repo"; rm -rf "$V"
git clone -q --local --no-hardlinks . "$V"
```

Afterwards, confirm the sandbox held: the real tree must be untouched, and
`git -C "$V" diff` shows exactly what the agent changed — read that diff, it is
the actual result of the run.

## Run it

Host Ollama with a capable model is the only practical setup; a 3B model in the
container cannot drive multi-step work. `DOCKER_BUILDKIT=1` is required on this
machine.

```bash
DOCKER_BUILDKIT=1 docker build -t penstock:validate .
docker run -d --name penstock-val --read-only --tmpfs /tmp:exec --tmpfs /home/agent/.cache \
  -v val-gradle:/home/agent/.gradle -v val-data:/data -v "$V":/workspace \
  -e AGENT_STORAGE_TYPE=sqlite -e AGENT_LLM_PROVIDER=ollama \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 -e OLLAMA_MODEL=qwen3-coder:30b \
  --add-host host.docker.internal:host-gateway -p 8091:8080 --memory 3g penstock:validate
```

Drive one task per request and keep the JSON:

```bash
curl -s --max-time 3000 -X POST http://localhost:8091/api/chat \
  -H 'Content-Type: application/json' -d '{"message":"<the task>"}' > "$SCRATCH/task.json"
```

## Record, per task

From the response: assistant turns, `usage.inputTokens`, which tools were called,
whether any `toolResults` contain `[output truncated`, and whether it finished or
hit a bound. From the clone: the diff.

Use tasks that stress different things — a search, a single-file edit, a
multi-file change with a test, and a build/test run. The failures are the point;
a run where everything passes has told you very little.

## Rules that keep the results honest

- **Use the prompt verbatim when re-testing.** Rewording changes the result:
  saying "run `gradle test`" instead of "`./gradlew test`" primes the model past
  the exact failure you were trying to observe.
- **One sample per configuration is not a measurement.** Say so in the write-up
  rather than implying causation. A fix and a green run are not proof the fix
  caused it — the unit tests are what establish behaviour.
- **Record what was NOT exercised.** The 16 KB truncation cap has survived three
  validation rounds untested because real Gradle output is smaller than expected.
  Saying so is more useful than quietly implying coverage.
- **Report the failures in full.** They are the reason to run this at all.

## Then

Append a dated section to `docs/real-repo-validation.md` with the table, the
diff of anything the agent changed, and a plain verdict on what it can and cannot
do. File an issue per distinct defect and link it.
