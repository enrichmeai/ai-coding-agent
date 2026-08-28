---
name: verify-published-claim
description: Verify any claim about Penstock's published artifacts (releases, images, SBOMs, scan reports, visibility) against a primary source before publishing it — including your own claims and ones a teammate hands you. Use before any statement that reaches the owner, a PR body, release notes, another session, or the website.
---

# Verify a claim before publishing it (Penstock)

The rule: a claim about a published artifact gets checked against the artifact, not against memory, a prior message, or the person who said it. This applies to your own claims and to corrections a teammate hands you — both directions have been wrong here.

## Worked examples (each one happened)

1. **A 401 from GHCR does NOT mean private.** GHCR returns 401 to every tokenless request, public packages included. The correct visibility test is an anonymous token:
   ```bash
   TOK=$(curl -s "https://ghcr.io/token?scope=repository:enrichmeai/penstock:pull" | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
   curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOK" https://ghcr.io/v2/enrichmeai/penstock/manifests/<tag>
   # 200 = public. 403 with a valid anonymous token = private.
   ```
   Getting this wrong nearly sent the owner to "fix" a setting that was already correct.

2. **Never count a scan report's rows with grep.** Trivy's table groups findings and does not repeat the severity cell per row; cell-counting produced "9" against a true total of 138 — and a second attempt produced "3+4". Read the tool's own `Total:` line, then decompose by location: `agent.jar` findings are runtime-reachable; `/opt/gradle-seed` and Gradle-distribution jars are toolchain tail. Both numbers matter; conflating them turns an afternoon into an emergency or vice versa.

3. **Phrase to survive scrutiny.** "Known-fixable criticals fixed; one app-level CVE awaits an upstream release" survives; "clean scan" does not. If a fix version isn't on Maven Central, say so — check before promising a pin exists:
   ```bash
   curl -s "https://search.maven.org/solrsearch/select?q=g:<g>+AND+a:<a>&rows=12&core=gav" | python3 -c "import json,sys; [print(d['v']) for d in json.load(sys.stdin)['response']['docs']]"
   ```
   Note: results are relevance-sorted, not latest-first — for the true latest, query without `core=gav` and read `latestVersion`.

## Standing checks for Penstock's artifacts

- Release exists + assets: `gh release view vX.Y.Z --repo enrichmeai/penstock --json url,assets`
- Image is genuinely multi-arch: fetch the OCI index with the anonymous token (above) and list `platform.architecture` — expect amd64 + arm64.
- A workflow "watch" exiting 0 does not mean success — `gh run watch` on an already-completed run exits 0 regardless; read `--json conclusion`.
- A version number in a report is the version *scanned*, not necessarily the version *shipped* — confirm `/actuator/info` on the running artifact when it matters.

## When a teammate corrects you

Verify the correction the same way you'd verify the original claim, then reply with what the primary source said — conceding, standing firm, or (most often) refining both positions. Twice in one day here, the check was worth more than the confidence.
