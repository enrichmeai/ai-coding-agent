# Security Policy

## Purpose

This document describes the security threat model, in-scope and out-of-scope threats, and hardening guidance for Penstock.

## In-Scope Threats

### 1. Host Escape via Shell Tool
**Threat:** Malicious or poorly-controlled shell commands could escape the container and compromise the host system.

**Mitigations:**
- Shell tool runs inside a container (process isolation).
- Container hardening via `docker-compose.yml`: `read_only: true`, `cap_drop: ALL`, `pids_limit`, memory limits.
- Expanded block-list in `application.yml` blocks common exfiltration vectors: `curl`, `wget`, `nc`, `ssh`, `scp`, `rsync`, `ftp`.
- Optional allow-list (`Tools.Shell.allowedCommands`) restricts execution to a curated set of commands (e.g., `[ls, git, ./gradlew, grep]`).

### 2. Workspace Path Traversal
**Threat:** Commands or file operations could traverse outside the workspace directory.

**Mitigations:**
- `WorkspacePath.resolve()` validates and restricts file operations to the workspace root.
- The workspace is mounted as a volume; file operations cannot escape this boundary.

### 3. Credential Exfiltration over Network
**Threat:** Commands or LLM-driven actions could leak credentials (API keys, SSH keys, etc.) to external hosts.

**Mitigations:**
- Block-list prevents `curl`, `wget`, `nc`, `ssh`, `scp`, and related tools.
- Production deployments should run with `--network=none` to completely disable network access (except for the agent service itself if needed for LLM API calls).
- Credentials (API keys) are stored in environment variables, not in the workspace.

### 4. Per-User Data Leakage
**Threat:** In a multi-tenant scenario, one user could access another user's workspace or session data.

**Mitigations:**
- **Phase 1.4** (session ownership): Each session is owned by an authenticated user; the agent enforces user-scoped access to workspaces.

### 5. Cost Runaway
**Threat:** Unbounded LLM API calls or infinite loops could incur unexpected costs.

**Mitigations:**
- **Phase 1.2** (token budgets): Per-session and per-request token limits enforce cost boundaries.
- **Phase 1.3** (rate limits): Request rate limiting (configurable via `RateLimit.Bucket`) prevents abuse.

## Out-of-Scope Threats

The following threats are explicitly **out-of-scope** and not defended against in this phase:

### 1. Trusting the LLM with Destructive Write Access
**Threat:** The LLM could maliciously or erroneously delete workspace files.

**Mitigation:** Use version control (git). The agent can read files but should always be under user oversight when modifying code. Critical data should be backed up.

### 2. Authenticated User Acting Maliciously Against Their Own Data
**Threat:** A legitimate user with valid credentials could delete their own files or manipulate their workspace.

**Mitigation:** This is the user's responsibility. The agent provides auditing (session logs), not prevention.

### 3. Advanced Container Escapes (Kernel CVEs)
**Threat:** A zero-day kernel vulnerability could allow a process to escape the container.

**Mitigation:** Deploy behind a hardened kernel; apply OS security patches regularly. Use a distroless or minimal base image (we use `eclipse-temurin:21-jre-jammy`, which is kept patched).

## Hardening Checklist for Production

- [ ] Enable authentication: `AGENT_AUTH_ENABLED=true` and set strong credentials (or use an identity provider).
- [ ] Enable rate limiting: `AGENT_RATE_LIMIT_ENABLED=true`.
- [ ] Configure allow-list: Set `AGENT_TOOLS_SHELL_ALLOWED_COMMANDS` to a minimal set (e.g., `[ls,cat,git,rg,./gradlew]`).
- [ ] Run with `--read-only` flag in Docker to prevent runtime modifications.
- [ ] Run with `--network=none` (or restrict to LLM API endpoints only) to disable exfiltration.
- [ ] Run with `--cap-drop=ALL` to drop all Linux capabilities.
- [ ] Set memory limits: `--memory=1g` or appropriate for your deployment.
- [ ] Rotate API keys regularly and use a secrets manager (not plain text in `application.yml`).
- [ ] Use per-workspace read-only mounts where the agent only needs to read (write operations require explicit volume mount).
- [ ] Keep the base image and dependencies up-to-date; scan for vulnerabilities regularly.
- [ ] Monitor container logs and set up alerts for error rates or suspicious commands.

## Reporting a Security Vulnerability

If you discover a security vulnerability in Penstock, please do **not** open a public issue. Instead, please contact the security team:

- **Email:** security@example.com
- **Private Issue:** Open a confidential security issue in this repository (if supported).

We will acknowledge your report within 48 hours and work with you to develop a fix.

---

**Last Updated:** 2026-04-20  
**Version:** 1.0 (Phase 1.1)
