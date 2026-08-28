---
name: cut-release
description: Cut a Penstock release — preconditions, tag push, watching the pipeline, and independently verifying the published release, multi-arch image, and scan report. Use when asked to release, re-release after a failed run, or verify a release.
---

# Cut a release (Penstock)

Tag push → `.github/workflows/release.yml` does everything. Your job is preconditions before and independent verification after. Merging and tag-pushing are outward actions — confirm the user has asked for this release.

## 1. Preconditions

- `main` green (`gh pr checks` on the last PR, or the latest `build.yml` run).
- `build.gradle` default `version` matches the tag you're about to cut (the workflow overrides via `-PreleaseVersion`, but drift confuses local builds).
- Run `/ship-check` locally if anything touching the image/deps landed since the last release.

## 2. Tag

```bash
git checkout main && git fetch origin && git merge --ff-only origin/main
git tag -a vX.Y.Z -m "vX.Y.Z — <one-line theme>

<what changed, notable numbers>"
git push origin vX.Y.Z
```

## 3. Watch

```bash
RUN_ID=$(gh run list --repo enrichmeai/penstock --workflow=release.yml --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RUN_ID" --repo enrichmeai/penstock --exit-status
```

Run in the background; on completion check `--json conclusion` — `gh run watch` on an already-finished run can exit 0 even for a failed run, so never trust the watch exit code alone.

## 4. Verify independently (all three, every time)

```bash
# Release + assets exist
gh release view vX.Y.Z --repo enrichmeai/penstock --json url,assets -q '.url + " | " + ([.assets[].name]|join(", "))'

# Image is multi-arch AND anonymously pullable (bare GHCR GETs 401 even for
# public packages — always use an anonymous token; a 401 does NOT mean private)
TOK=$(curl -s "https://ghcr.io/token?scope=repository:enrichmeai/penstock:pull" | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
curl -s -H "Authorization: Bearer $TOK" -H 'Accept: application/vnd.oci.image.index.v1+json' \
  https://ghcr.io/v2/enrichmeai/penstock/manifests/X.Y.Z | python3 -c "import json,sys; d=json.load(sys.stdin); print(sorted(m['platform']['architecture'] for m in d['manifests'] if m['platform']['architecture']!='unknown'))"

# Scan headline — read Trivy's OWN Total line; never count table rows by
# grepping severity cells (grouped rows undercount catastrophically)
gh release download vX.Y.Z --repo enrichmeai/penstock --pattern "trivy-report-*" -O - | grep "Total:"
```

When reporting scan numbers, decompose by location: findings in `agent.jar` are runtime-reachable; findings in `/opt/gradle-seed` or the bundled Gradle distribution jars are toolchain, not app.

## 5. Failure lore

- Failed run = nothing published (the smoke test gates the push) → fix, then move the tag: `git tag -f -a vX.Y.Z <sha> && git push --force origin vX.Y.Z`. Never move a tag whose release published.
- 8-second failures are action-resolution errors (e.g. `trivy-action` pins are `v`-prefixed); ~30-second failures at "Bootstrap Gradle wrapper" are transient CDN issues — `gh run rerun <id> --failed`.
- After it lands: notify the coordinating Cistern session if one is active — the site links releases, and the T6.3 demo compose pins Penstock by **explicit tag** (deliberately, so readers get the image the walkthrough was written against). It does not follow `:latest`; every release needs that pin bumped consciously on their side, so tell them the new version explicitly.
