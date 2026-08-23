# Security Policy — Aderon3D

> **AI-generated code.** Most of Aderon3D is written with AI assistance. It may
> contain bugs or insecure patterns. Treat every merge as untrusted until the
> automated gates below pass.

## Reporting a vulnerability

Do **not** open a public issue for security problems. Use GitHub's
**Private vulnerability reporting** (repo → Security → Advisories → "Report a
vulnerability"). We aim to triage within 72 hours.

## Automated defenses (all enabled in this repo)

These run on every PR to `main` and on every push to `main`:

| Guard | Workflow | What it catches |
|---|---|---|
| Build & test | `build.yml` | AI code that does not compile or breaks the M0.5 host tests |
| CodeQL Advanced | `codeql.yml` (security-and-quality) | Insecure patterns, bugs, correctness issues in Kotlin/Java + workflow YAML |
| Dependency review | `dependency-review.yml` | PRs introducing vulnerable or license-violating dependencies |
| OSV Scanner | `osv-scanner.yml` | Known CVEs in the dependency graph (SARIF → code scanning) |
| Secret scan | `secret-scanning.yml` | Committed API keys / tokens (Gitleaks) |

### Enable in repo Settings (one-time, owner only)

1. **Settings → Branches → Add rule** (branch `main`):
   - ☑ Require a pull request before merging
   - ☑ Require approvals: **1**
   - ☑ Require status checks to pass → select `Build & Test`, `CodeQL Advanced`, `Dependency Review`, `OSV Scanner`, `Secret Scan`
   - ☑ Require conversation resolution before merge
   - ☑ Do not allow bypassing the above
   - ☑ Restrict force pushes & deletions
2. **Settings → Code security →** enable **Dependency graph**, **Dependabot alerts**,
   **Dependabot security updates**, **Code scanning** (we supply the config),
   **Secret scanning** + **Push protection**.
3. **CODEOWNERS** (`.github/CODEOWNERS`) already requires `@Aderon3D/maintainers`
   review on every file.

With these rules, nothing reaches `main` without a passing build, a clean
CodeQL/OSV/secret scan, and a human review.
