# Nightly Test Triage Workflow

Automatically diagnose failing tests and file triage issues using the AI agent.

## What it does

Runs nightly at 2:00 AM UTC (configurable via cron). Fetches the last 10 failing test runs from GitHub Actions, sends them to the hosted agent for analysis, and files a draft issue with root cause summaries and suggested fixes.

## Setup

### 1. Add GitHub Secrets

Go to your repository **Settings > Secrets and variables > Actions** and add:

- `AGENT_URL`: Base URL of the deployed agent (e.g., `https://agent.example.com`)
- `AGENT_AUTH`: HTTP Basic auth credentials in format `username:password` (base64-encoded by curl)

### 2. Install the Workflow

Copy the `.github/workflows/nightly-triage.yml` file into your repository's `.github/workflows/` directory. If the directory doesn't exist, create it.

```bash
mkdir -p .github/workflows
cp nightly-triage.yml .github/workflows/
git add .github/workflows/nightly-triage.yml
git commit -m "Add nightly test triage workflow"
git push
```

### 3. Trigger Manually (Optional)

Go to **Actions > Nightly Test Triage > Run workflow** to test immediately.

## Estimated Cost

Per run, assuming Claude 3.5 Sonnet:
- Input: ~500 tokens (prompt + test summaries)
- Output: ~300 tokens (root cause analysis)
- **Cost per run: ~$0.005 USD**
- **Monthly (30 runs): ~$0.15 USD**

(Costs vary by LLM provider and agent tier.)
