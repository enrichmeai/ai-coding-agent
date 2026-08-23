# Running the agent fully offline with Docker Compose

The stack in `docker-compose.yml` runs the agent *and* its LLM on your machine.
After a one-time model pull it needs no internet and no API keys.

```
┌─────────────────┐        ┌──────────────────┐
│ agent           │  HTTP  │ ollama           │
│ Spring Boot     │───────▶│ llama3.2:3b      │
│ :8080 → :8090   │        │ (compose network)│
└────────┬────────┘        └──────────────────┘
         │ bind mount
         ▼
   ./agent-workspace      ← the only directory the agent may touch
```

## Quick start

```bash
mkdir -p agent-workspace              # the folder the agent is allowed to edit
docker compose up -d --build          # first run pulls the model (~2 GB)
open http://localhost:8090            # web UI
```

`ollama-pull` is a one-shot service that fetches the model into the
`ollama-models` volume and exits. It is the only step that needs internet; it
skips the download if the model is already present. Everything after that runs
offline — disconnect and it still works.

Ports: the UI is on **8090** by default (8080 inside the container). Override
with `AGENT_PORT=9000 docker compose up -d`. The bundled Ollama is deliberately
not published to the host, so it cannot collide with an Ollama you already run.

## Proving it works

```bash
./scripts/demo-offline.sh
```

The script checks health, drives one full tool-use turn through `POST /api/chat`,
and verifies the file actually appeared in `agent-workspace/` on the host. A
successful run looks like:

```
[1/5] health                    ok (provider=ollama, 9 tools)
[2/5] model is served locally   ok (llama3.2:3b in the ollama container)
[3/5] tool-use turn             ok (write_file → hello.txt)
[4/5] file on host              ok ("Hello from a fully offline agent")
[5/5] history persisted         ok (USER, ASSISTANT, TOOL, ASSISTANT)
```

## Choosing a model — this is the part that actually decides whether it works

A model must emit tool calls in Ollama's structured `tool_calls` field. Many
models advertise `tools` in `ollama show` and still return the call as prose,
which historically left the agent loop with nothing to execute. The agent now
recovers the two common text formats (see `TextToolCallParser`), so these models
work — but native structured output is still more reliable.

Measured by sending this repo's real payload (all 9 tool schemas, ~1.6k prompt
tokens, the question "What files are in the workspace directory?") to each model
four times and counting how often the call came back in the structured field:

| Model | Size | Native `tool_calls` | Notes |
|---|---|---|---|
| `llama3.2:3b` | 2.0 GB | 4/4 | Default. Small enough for a modest Docker VM, but the weakest reasoner — it sometimes invents a tool name. |
| `llama3.1:8b` | 4.9 GB | 4/4 | Same reliability, better instruction-following. Worth it if your VM has the room. |
| `qwen3-coder:30b` | 18 GB | 1/4 | Strongest writer, but usually emits Qwen's `<function=…>` XML; works only because the fallback parser recovers it. Needs a GPU host. |
| `qwen2.5-coder:3b` | 1.9 GB | 0/4 | Always returns fenced JSON as text. The fallback recovers the well-formed cases, but it is the least dependable — avoid. |

Sizes are what Ollama reports; the 30B figure is also roughly its resident
memory, which is why it does not fit a default Docker Desktop VM.

Change it with `OLLAMA_MODEL=llama3.1:8b docker compose up -d`.

## Context window — set it or lose your system prompt

Ollama does not use a model's full context window unless you ask. Left alone it
applies its own default and **silently discards whatever does not fit**, starting
at the front of the payload: the system prompt and the tool schemas. Nothing
errors and nothing is logged, so the only symptom is the agent quietly getting
worse.

Measured here with `llama3.2:3b` and a ~7,900-token prompt carrying a marker at
the very start:

| Path | `num_ctx` | Tokens actually evaluated | Recovered the marker? |
|---|---|---|---|
| Bundled container | default | **2050** | no — answered about something else entirely |
| Host Ollama 0.12.11 | default | **4096** | no — same |
| Either | 8192 / 16384 / 32768 | 7050 | yes |

The model itself advertises a 131,072-token context and ships no `num_ctx`
override, so this is purely about what the client asks for.

The agent therefore sends `options.num_ctx` on every request, from
`agent.llm.ollama.num-ctx` (`OLLAMA_NUM_CTX`), defaulting to **16384**. That is
measured safe for a 3B model in an 8.2 GB Docker VM — 32768 also ran without an
OOM kill. For scale, a bare first turn already costs ~1,600 tokens of system
prompt and tool schemas, and a single tool result can add ~4,000 more, so the
bundled default of 2050 is exceeded by about the second tool call of a real
session.

Two things to keep in mind:

- **KV cache is allocated up front and scales with `num_ctx`.** A bigger model
  plus a big window can exhaust the VM: `llama3.1:8b` (4.9 GB resident) at 32768
  is far closer to the edge than `llama3.2:3b` at 16384. If the ollama container
  starts dying, check `docker inspect ai-coding-agent-ollama --format '{{.State.OOMKilled}}'`
  before suspecting anything else.
- **`num-ctx: 0` omits the option**, letting a server-side `OLLAMA_CONTEXT_LENGTH`
  win instead. Use that if you manage the Ollama server's configuration yourself.

The agent logs a WARN, once per session, when a prompt uses more than 80% of the
window — that is the signal you are about to start losing the front of the
conversation.

## Faster: use an Ollama running on the host

On macOS, containers get no Metal/GPU access, so the bundled Ollama is CPU-only
and a 30B model is impractical inside it (check your VM's ceiling with
`docker info --format '{{.MemTotal}}'`). Point the agent at a host Ollama
instead — still entirely local:

```bash
ollama serve && ollama pull qwen3-coder:30b     # on the host
docker compose -f docker-compose.yml -f docker-compose.host-ollama.yml up -d
```

## Notes for anyone changing this stack

- **`/tmp` must be mounted `exec`.** sqlite-jdbc unpacks a native `.so` into
  `/tmp` and `dlopen()`s it. A default (noexec) tmpfs makes Flyway die with
  `UnsatisfiedLinkError` — and because the container is otherwise read-only, the
  failure is easy to misread as a permissions problem.
- **SQLite lives on a named volume, not a bind mount.** On Docker Desktop a bind
  mount is proxied through gRPC-FUSE, where SQLite's locking is unreliable.
  Extract the database with `docker compose cp agent:/data/agent.db ./agent.db`.
- **The SQLite pool is capped at one connection.** SQLite allows a single writer,
  and `AuditLogger` writes from a different thread than the agent loop; a larger
  pool produces `SQLITE_BUSY` partway through a conversation.
- The agent container keeps its hardening (`read_only`, `cap_drop: ALL`,
  `no-new-privileges`, `pids_limit`, 1 GB cap). Don't copy those onto the Ollama
  service — it needs several GB to hold a model.
