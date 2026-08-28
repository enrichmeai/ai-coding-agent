---
name: pr-sweep
description: Survey open PRs, branches and local-vs-remote state before starting work, opening a PR, or reporting repo status. Use at the start of any session that will change the repo, before claiming something is or is not merged, and whenever another session may be working in the same checkout.
---

# PR sweep

Several sessions work in this repo at once, sometimes in the same checkout.
Every mistake below actually happened, most of them in a single week, and each
one produced a confident wrong answer rather than an error.

## Run this before touching anything

```bash
git fetch origin                       # FIRST. Everything below lies without it.
gh pr list --repo enrichmeai/penstock --state open \
  --json number,title,headRefName,isDraft,mergeable,author \
  --jq '.[] | "#\(.number) \(.headRefName) mergeable=\(.mergeable) by=\(.author.login) — \(.title)"'
gh issue list --repo enrichmeai/penstock --state open
git status --short
echo "ahead $(git rev-list --count origin/main..main) behind $(git rev-list --count main..origin/main)"
git log --oneline main..HEAD          # what is on THIS branch that main lacks
```

## The five failures this exists to prevent

**1. Checking issues but not PRs.** `gh issue list` does not show pull requests.
A session once planned an integration against `main`, unaware that two PRs had
already merged into it, and built a local `main` that duplicated one merged
change and omitted another. **Always list both.**

**2. Not fetching.** `git rev-list --count origin/main..main` against a stale
`origin/main` returns a precise, confident, wrong number. It reported "8 ahead,
0 behind" when the truth was "8 ahead, 2 behind" and the branch was not
fast-forwardable. Fetch, then count.

**3. Trusting `git branch --merged` where PRs are squash-merged.** A squashed
branch never appears merged, because its commits are not ancestors of `main`. Use
content, not ancestry:

```bash
for b in $(git branch --format='%(refname:short)' | grep -v '^main$'); do
  [ -z "$(git cherry main "$b" | grep '^+')" ] \
    && echo "$b — fully on main, safe to delete" \
    || echo "$b — has unmerged commits"
done
```

**4. Another session's commits landing on your branch.** The checkout is shared.
If you switch it to your branch, a teammate's next commit and push land on
*your* branch and appear in *your* PR. This happened within an hour of the
gotcha being written down. Before opening a PR, read `git log --oneline main..HEAD`
and confirm every commit is one you meant to include. If a passenger is there,
**do not silently rebase it away** — ask whose it is first; it is usually
deliberate work that now depends on riding along.

Prevention: work in `git worktree` rather than switching the shared checkout, and
stage explicit paths — never `git add -A`. A `git add -A` in a stale shared tree
once silently reverted four merged PRs and still compiled green, because the
tests that would have caught it were among the reverted files.

**5. Two PRs quietly doing the same work.** Sessions duplicate effort without
colliding in git. Compare file lists before adding to the pile:

```bash
for n in $(gh pr list --repo enrichmeai/penstock --state open --json number --jq '.[].number'); do
  echo "--- #$n"; gh pr view "$n" --repo enrichmeai/penstock --json files --jq '.files[].path'
done
```

Overlap is not automatically a problem — the fix may be to close one as
superseded rather than to merge both. Decide with the other session, not alone.

## Reviewing thoroughly

**Verify a claim against current `main` before acting on it**, including one from
a teammate — and check it *part by part*, because reports are rarely uniformly
right or wrong. A session once reported two defects: the first was already fixed,
and the second had two halves of which only one was — the CORS misconfiguration
was fixed, while the auth-disabled-by-default half it was bundled with is still
true today. The premise attached to both, that the repo was private and so the
changes were free, had also gone stale: the repo is public.

Answering "both already fixed" would have been as wrong as accepting the report.
Read each claim against the file on `main`; do not trust a remembered line
number or an editor buffer, which may be many merges stale.

**Confirm a review request actually attached.** Requesting the Copilot reviewer
returned HTTP 200 while `requested_reviewers` stayed empty — the bot was not a
collaborator. A 200 is not evidence:

```bash
gh api repos/enrichmeai/penstock/pulls/<N>/requested_reviewers --jq '[.users[].login]'
```

**Check CI before merging, not after.**

```bash
gh pr view <N> --repo enrichmeai/penstock \
  --json state,mergeable,statusCheckRollup \
  --jq '"state=\(.state) mergeable=\(.mergeable) checks=\([.statusCheckRollup[]? | "\(.name):\(.conclusion // .status)"] | join(", "))"'
```

## Where this stops

This skill covers PR, branch and local-state survey only. It deliberately does
not restate procedures that live elsewhere — one source of truth per procedure:

- **Does it still ship locally?** → `ship-check`
- **Tagging, watching the pipeline, verifying what was published** → `cut-release`
- **Checking a claim about a release, image, SBOM or scan report against a
  primary source** → `verify-published-claim`

## After a merge

```bash
git checkout main && git pull --ff-only origin main
```

Then re-run the `git cherry` loop above and delete branches whose content has
landed. Confirm the issues the PR claimed to close are actually closed — a
`Closes #N` in the body only fires when the PR merges to the default branch.

## What to report

Say which PRs are open and who owns them, whether local `main` matches
`origin/main`, which local branches are safe to delete, and anything a teammate
believes that the repo contradicts. Never report merged/unmerged status from
memory — this file exists because memory was wrong every time.
