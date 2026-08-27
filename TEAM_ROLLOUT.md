# Team Rollout Playbook — Penstock

Six-week plan from "built and reviewed on my laptop" to "default tool used by the team." Aimed at engineering leads. Assumes Phase 1 (safety) and Phase 2 (operability) are landed.

---

## Week 0 — Pre-launch (before anyone touches it)

Two days of setup work. Nothing is public yet.

**Infrastructure (one instance, not thirty).**
Deploy to a single internal VM or k8s namespace — whichever your team already has capacity for. Set these env vars:

| Setting | Value | Why |
|---|---|---|
| `AGENT_AUTH_ENABLED` | `true` | Everything is behind Basic until Phase 3 OIDC |
| `AGENT_AUTH_USERNAME` / `PASSWORD` | Strong shared pilot creds | Rotate after pilot |
| `AGENT_STORAGE_TYPE` | `sqlite` (or `postgres` at Phase 3) | Audit log needs persistence |
| `AGENT_LLM_PROVIDER` | `anthropic` | Best coding performance; easy to swap |
| `ANTHROPIC_API_KEY` | Fresh key with a **$200 monthly cap** | Budget guardrail at the provider level |
| `AGENT_RATE_LIMIT_ENABLED` | `true` | Default 30/min chat, 300/min api is sensible |
| `agent.llm.max-tokens-per-session` | `200000` | ~$1 Sonnet cap per session |
| `agent.tools.shell.allowed-commands` | `[ls, cat, rg, grep, find, head, tail, wc, git, ./gradlew, mvn, npm, pytest]` | Opt into the allow-list for shared instance |

**Scrape metrics and logs.**
Point your Prometheus at `/actuator/prometheus`. Point your log aggregator (Datadog/Splunk/Loki) at the `prod` profile's JSON output. Create a dashboard with four panels: active streams, tokens/sec by provider, error rate, tool calls by name.

**Write three assets that will shape adoption.**

1. A 1-page "getting started" internal wiki entry: URL, how to log in, 3 example tasks, what NOT to do (never paste secrets, always review PRs).
2. A SECURITY.md sign-off from your security/compliance team. Use the in-repo [SECURITY.md](./SECURITY.md) as the basis.
3. Three to five **system prompts** pre-staged for different domains — backend, frontend, devops, etc. Swap via config per deployment, or build a prompt selector into the UI (small patch).

---

## Weeks 1–2 — Pilot (3–5 hand-picked people)

**Pick the right five.** One senior, two mid-level, one junior, and deliberately one grumpy sceptic. The sceptic's objections are worth more than a friendly early-adopter's praise; their pushback is the v2 roadmap.

**Give them real work, not demos.** Ask each to attempt 10 tasks they would have done manually. Ideal mix:

1. A multi-file refactor (rename, move, extract).
2. A test-fix loop ("this is red, make it green").
3. A scaffolding task (new endpoint, new module).
4. A code-archaeology task ("trace where order X is handled").
5. A PR review.

They keep a running log: what worked, what was wrong, how long it saved. A shared Google Sheet is fine.

**30-minute weekly sync.** Review the audit log (the actual queries: top tools, top session titles, error rate per tool). Tune the system prompts. Identify two specific frustrations to fix for week 2.

**Exit criteria before expanding.** At least three of the five say "I'd miss it if it were gone." If you get fewer than three, don't expand — fix the frustrations first.

---

## Weeks 3–4 — Expansion (team-wide, opt-in)

**Announce in the lowest-drama way possible.** A short Slack message with the getting-started link, 2–3 specific tasks to try, and explicit "this is opt-in, pilot users say it saves ~2 hours/week on X tasks." No mandate. Mandates breed resistance.

**Office hours, twice a week for two weeks.** 30 minutes. Someone watches a new user do one real task. You will learn more from 10 minutes of watching than 10 user interviews.

**Start measuring publicly.**

*Leading indicators* (weekly): active users, sessions/day, tokens/day, tool-call success rate.

*Lagging indicators* (monthly): cycle time on tickets that had agent sessions attached; time-to-green for flaky tests; first-PR time for new hires.

**Collect three success stories.** Specific ("X refactored 14 files in 20 minutes") and three failure stories ("Y spent 45 minutes fighting it and gave up"). The failure stories are how you earn credibility for the next rollout.

---

## Weeks 5+ — Normalization

Change the defaults. New-hire onboarding day 1 covers it. CI gets a hook where the agent drafts PR descriptions or responds to review requests. Show-and-tells become monthly, not weekly.

At week 8, do a **quarterly review**: cost vs value. Expect to spend roughly $20–60 per active developer per month on tokens; if you're saving 3+ hours per developer per month, the ROI is obvious. If you're not, something is wrong with prompts or tasks, not with the product.

---

## The three decisions that matter most

1. **Self-host vs. "use Copilot harder."** If your org already has Copilot Business, the agent's value is specifically in the things Copilot cannot do: autonomous multi-step loops, custom tools talking to your internal systems, auditable action trails, and private data. If those don't apply, stick with Copilot. If any two do, self-host.

2. **Shell tool policy.** The single biggest governance decision. For a pilot, allow-list mode with the dozen commands above is safe. For production, run in a container (compose file already hardened) or a disposable sandbox. Revisit every 3 months.

3. **Data boundary.** Which repos can the agent read/write? Start narrow (one pilot repo per team). Broaden after 4 weeks of clean audit logs.

---

## Shared assets to build over time

As usage grows, invest in a small internal library:

**System prompts** per domain. Our backend team has one that knows our logging conventions and ORM patterns; the frontend team's one knows our design system. 200 words, committed to your internal repo.

**Session templates** — named sessions for common jobs like `review-pr`, `fix-failing-test`, `scaffold-crud-module`. These are pre-populated system prompt + first user message. A dropdown in the UI.

**Custom tools** — the real unlock. The 8 built-in tools (read/write/edit/grep/glob/shell/git/list) are the baseline. Your leverage comes from adding `Tool` subclasses for your internal systems: Jira, Slack, internal docs, your runbook, your observability stack. 30–50 lines of Java each. Three of these and the agent starts doing things Copilot structurally cannot.

---

## Governance checklist

- [ ] Security review signed off (reference [SECURITY.md](./SECURITY.md)).
- [ ] Data classification: which repos can the agent touch — documented.
- [ ] PR policy: agent-authored commits **require human review**. No auto-merge, ever.
- [ ] Incident response: on-call rotation includes the agent. Alerts on 5xx rate, token burn rate, circuit breaker open.
- [ ] Quarterly cost review with finance.
- [ ] Audit log retention policy (we recommend 90 days minimum for security review purposes).
- [ ] Secret scrubbing in the system prompt — add explicit "never echo API keys or credentials you encounter in files."

---

## The objections you'll hear (and the honest answers)

**"It'll write bad code."** Yes, sometimes. That's why human PR review is mandatory. Start with low-stakes tasks (tests, scaffolding, refactors). You'll see the quality curve improve with each prompt iteration.

**"It's a security risk."** The audit log makes every action traceable to a user. The allow-list constrains the shell. Per-user session isolation prevents cross-team leaks. You're strictly safer than handing engineers `sudo` on the build server.

**"Copilot already does this."** Copilot is for inline completions and single-file Q&A. This is for autonomous multi-step tasks. They compose — most teams end up using both.

**"What if the LLM provider goes down?"** Circuit breaker + retries are built in. The agent degrades gracefully — it stops accepting requests with a clear error. Normal code review and IDE workflows continue unaffected.

**"What if it costs too much?"** Hard caps at three levels: provider-side monthly budget, per-session token cap, per-request token cap. The most expensive runaway is bounded at a few dollars.

**"Who owns this?"** Name a Directly Responsible Individual at kickoff. Usually the eng lead rolling it out. On-call rotates within their team.

---

## Metrics dashboard — minimum viable

A Grafana dashboard with these panels:

1. Active users (weekly) — stacked by team.
2. Sessions per day — line chart.
3. Tokens per day — stacked by provider.
4. Cost per day — same data × price per token.
5. Tool call breakdown — bar chart by tool, split ok/error.
6. P50/P95 LLM latency — by provider.
7. Active SSE streams — gauge.
8. Rate-limit rejections — counter.

The first time you show this to your VP, they will approve more budget.

---

## When to say no (or stop)

Pull the plug if, after 6 weeks:

- Fewer than 30% of the invited team are still actively using it.
- Audit log shows >20% of tool calls are `shell` with no clear task pattern (indicates misuse or confusion).
- Token spend is growing linearly with time without corresponding productivity gains.
- Two or more PRs caused by the agent required rollback.

Not every team is ready. Sometimes the answer is "wait 6 months and try again with better prompts."

---

## Who does what during rollout

| Role | Owns |
|---|---|
| Eng lead (you) | Strategy, stakeholder comms, exit criteria |
| Platform/devops (1 person) | Deployment, monitoring, incident response |
| Pilot sceptic | Honest feedback, v2 feature list |
| Security sign-off | One-time review of SECURITY.md + data boundary |
| Finance liaison | Monthly cost review after week 4 |

Total FTE: roughly 0.3 for 6 weeks, then 0.1 steady-state.

---

## What's next after rollout

Once steady-state (likely week 8–10), the natural expansions are:

1. Add MCP-style custom tools for your internal systems (Jira, Slack, docs).
2. Phase 3 of the roadmap — OIDC, Postgres, multi-instance.
3. Planning/approval mode for dangerous tools.
4. Token streaming in the UI (Phase 4).
5. Team-specific system prompts served from config.

Don't do these in parallel with rollout. Fix what the pilot users complained about first.
