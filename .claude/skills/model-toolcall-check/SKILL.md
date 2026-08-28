---
name: model-toolcall-check
description: Measure whether a local model can actually drive Penstock's tool loop — sends the real 9-tool payload to Ollama N times and counts structured tool_calls vs text vs nothing. Use before making a model the default, when the agent "answers but never does anything", or when adding a model to the table in docs/offline-docker-compose.md.
---

# Model tool-call check

Penstock's loop acts **only** on structured `tool_calls`. A model that advertises
`tools` and answers in prose produces a turn that looks successful and does
nothing. Reading `ollama show` will not tell you — several models advertise tool
support and still fail. The only reliable answer is to send the real payload and
count.

Measured this way, the family differences are large and not guessable:
`llama3.2:3b` 4/4, `llama3.1:8b` 4/4, `qwen3-coder:30b` 1/4 (works only via the
fallback parser), `qwen2.5-coder:3b` 0/4 — and qwen2.5-coder was once the
shipped default.

## Why the real payload matters

A one-tool toy prompt passes on models that collapse under the actual system
prompt plus nine tool schemas (~1.6k prompt tokens). Always measure with the
tools the agent really sends, pulled live from a running instance.

## Procedure

Needs a running Penstock (any port) and Ollama reachable. Write the script to the
session scratchpad, never the repo.

```bash
curl -s localhost:8090/api/tools > "$SCRATCH/tools.json"   # the real specs, live
```

```python
import json, sys, urllib.request
model = sys.argv[1]; runs = int(sys.argv[2]) if len(sys.argv) > 2 else 4
tools = [{"type": "function", "function": {"name": t["name"],
          "description": t["description"], "parameters": t["inputSchema"]}}
         for t in json.load(open(f"{SCRATCH}/tools.json"))]
sysmsg = open("system-prompt.txt").read()   # copy from agent.llm.system-prompt
def run():
    body = {"model": model, "stream": False, "tools": tools,
            "messages": [{"role": "system", "content": sysmsg},
                         {"role": "user", "content": "What files are in the workspace directory?"}]}
    r = urllib.request.urlopen(urllib.request.Request(
        "http://localhost:11434/api/chat", data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"}), timeout=900)
    m = json.load(r)["message"]
    return bool(m.get("tool_calls")), (m.get("content") or "")[:60]
res = [run() for _ in range(runs)]
print(model, "structured:", sum(1 for r in res if r[0]), "/", runs)
for r in res: print("   ", r)
```

## Reading the result

- **N/N** — safe to default to.
- **Some/N** — nondeterministic. Sampling, not the prompt: temperature 0 does not
  fix it (measured — 1/4 either way on qwen3-coder). Usable only because
  `TextToolCallParser` recovers the text forms; note that in the docs.
- **0/N** — check *how* it failed. Qwen's `<function=…>` XML and fenced JSON are
  both recovered by the parser, so the model may still work end to end; anything
  else is unusable. Confirm by driving a real turn through `/api/chat`.

Prefer one run per configuration over none, but **do not draw conclusions from a
single sample** — run-to-run variance on local models is wide. Two runs are not
a measurement.

## Then

Update the model table in `docs/offline-docker-compose.md` with the real numbers
and the date. Never write a number you did not measure.
