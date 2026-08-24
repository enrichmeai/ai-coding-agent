# Real-repository validation

Everything the agent had been asked to do before this ran was a toy task in an
empty directory. This is the first run against a real codebase — a clone of this
repository at `f27248e`, mounted at `/workspace`.

Setup: agent in Docker, `qwen3-coder:30b` on host Ollama (Metal), `num_ctx`
16384 (issue #1), `max-turns-per-request: 10`. The agent worked on a throwaway
clone, never the real working tree.

## Results

| # | Task | Result | Turns | Input tokens | Wall clock |
|---|---|---|---|---|---|
| 1 | Add a Javadoc comment to `WorkspacePath` explaining the traversal guard | **pass** | 5 of 10 | 11,175 | 39s |
| 2 | Add a `HEAD` variant to the health endpoint plus a test | **fail** — hit the turn cap | 10 of 10 | 40,648 | 68s |
| 3 | Find every place `agent.llm.provider` is read | **pass** | 2 of 10 | 4,070 | 16s |
| 4 | Run the test suite and report whether it passes | **fail** — cannot build | 10 of 10 | 34,240 | 
| | | | | | ~2m |

Task 1 produced a correct, accurate comment describing what the code actually
does, after reading the file first. Task 3 found all 17 references in a single
`grep`. Both are genuinely useful work.

## Finding 1 — `list_dir` is single-level, and Java package trees are deep

The decisive cost in both failures. `ListDirTool` calls `Files.list(dir)` and its
schema exposes no depth or recursion parameter, so every level down is a separate
turn. Task 2 spent **six consecutive turns** doing nothing but descending:

```
src/test → src/test/java → src/test/java/com → .../example → .../agent → .../agent/controller
```

Six of ten turns gone before any work started. Task 4 lost three more the same
way. In a Java repo this is not an edge case, it is the normal shape of the tree.

Note the agent's own behaviour was reasonable: it tried `glob` first
(`**/AgentControllerTest.java`, then `**/*AgentController*Test.java`) and got
zero matches — **correctly**, because the file is named `AgentControllerIT.java`.
`glob` is not at fault. Falling back to walking the tree was sensible; the tool
just made it cost six turns.

## Finding 2 — the agent cannot compile or test the code it edits

Task 4 failed for three compounding reasons, and the agent diagnosed all of them
before running out of turns:

1. `./gradlew test` → `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`.
   `gradle-wrapper.jar` is gitignored (`.gitignore:15`), so it is absent from any
   clone; `bootstrap.sh` normally downloads it.
2. No system Gradle in the container (`which gradle` → nothing).
3. The runtime image is `eclipse-temurin:21-jre-jammy` — **a JRE**. There is
   `java` but no `javac`. Nothing could compile even with Gradle present.

And fetching the wrapper is not an option on this path: `curl` and `wget` are on
the shell block-list, and the whole point of the stack is to run offline.

This is the difference between an agent that can edit code and one that can
actually close the loop — write, compile, test, fix. Today it cannot verify its
own work.

## Finding 3 — the 16 KB tool-output cap is still untested

Task 4 was meant to exercise it, but never produced real test output, so the
question stands: the cap is unproven against a genuinely large result. Do not
treat it as validated.

## Verdict

**Works today:** answering questions about a codebase (`grep`/`glob` are strong
and cheap), and small single-file edits where the target is already known or one
search away. Task 1 is a fair example of the ceiling — read a file, make a
sensible change, verify it.

**Does not work today:** anything needing exploration of an unfamiliar tree, and
anything requiring the agent to build or test. That rules out most real coding
work, including the obvious "add a feature and a test for it".

Context is no longer the constraint — Task 2 used 40k input tokens across its
turns with no truncation warning, which before issue #1 would have been
impossible. The binding constraints now are turn economy and the missing
toolchain.

## Follow-ups

- #9 — make `list_dir` recursive with a depth limit
- #10 — give the agent a toolchain so it can build and test its own edits
- #4 — revisit `max-turns-per-request` with these numbers (was already gated on this run)
