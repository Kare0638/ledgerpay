# Security

LedgerPay is a portfolio project, not a production payment system: it holds no card data and runs
against a simulated PSP. This page covers the one kind of real secret it uses, API keys for external
services such as `GEMINI_API_KEY` for the reconciliation assistant (#26), and how they are kept out of
the repository.

## Reporting

Please report a vulnerability through GitHub's private vulnerability reporting on this repository,
not in a public issue.

## Secrets

**Where they live.** Only in the environment of the process that needs them: your shell, or a
GitHub Actions secret. Never in a tracked file: not `application.yml`, not `docker-compose.yml`, not a
`.env` file (`.env`, `.env.*`, `*.env` and `.envrc` are git-ignored), and not baked into an image
(the Dockerfiles take no `ARG` or `ENV` for them).

The rules for any service that takes one (the first is the assistant, #26):

- Configuration reads it as a placeholder with an empty default, for example `${GEMINI_API_KEY:}`.
  Without one the feature that needs it is off, and everything else starts.
- Compose passes it through from your shell: `GEMINI_API_KEY: ${GEMINI_API_KEY:-}`.
- Tests and CI never use a real key: they run against stubs.

**Kept out of requests and logs.** A key goes in a request header (`x-goog-api-key` for Gemini), never
in a URL, where it would end up in exception messages, access logs and traces. A test already checks
that the merchant and ops keys a request carries never reach the logs; the assistant's client gets the
same check, its failure paths included. Actuator exposes only `health` (status only) and
`prometheus` (behind the ops key); `env`, `configprops`, `heapdump` and the like are not exposed, and a
test checks that they answer 404.

**The values in the repository are not secrets.** `dev-only-ops-key` and the SHA-256 hashes in
configuration and tests are local development credentials, public by design, that work nowhere but a
local stack. They are listed one by one in `.gitleaks.toml`.

## Scanning

Three layers, each catching what the one before missed:

1. **Pre-commit hook** (`scripts/git-hooks/pre-commit`): scans the staged changes with
   [gitleaks](https://github.com/gitleaks/gitleaks) through Docker and refuses the commit, so a key
   never enters the local history. Enable it once per clone:

   ```bash
   git config core.hooksPath scripts/git-hooks
   ```

2. **GitHub push protection**: secret scanning with push protection is on for this repository, and
   refuses a push that contains a recognised key, Google API keys included.
3. **CI** (`secrets` job): scans every commit of the branch with the same `.gitleaks.toml`, so a key
   committed and then deleted is still found.

gitleaks' default rules include `gcp-api-key`, which matches Gemini keys (`AIza…`) in a configuration
value or in a URL's `?key=`. A false positive that is public by design goes in the allowlist in
`.gitleaks.toml`; never bypass the hook with `--no-verify` to commit a real key.

## If a key leaks

1. **Revoke it first**, then create a new one (for Gemini: delete it in Google AI Studio). Rewriting git
   history does not help once a key has been pushed or shared: copies may already exist.
2. Put the new key in your environment only.
3. Find where it went: `git log -p --all | grep -n 'AIza'`, the CI logs, and any chat or issue it was
   pasted into.

## Limiting the damage

For a Gemini key from Google AI Studio:

- Restrict the key to the **Generative Language API** in the Google Cloud console (APIs & Services →
  Credentials), so a leaked key can call nothing else.
- Keep billing **off** on its Google Cloud project. On the free tier a leaked key can use up the
  quota, but cannot run up charges.
- The free tier allows Google to use prompts and responses to improve its products. Only synthetic
  demo data is sent; a real deployment would need a paid tier or a self-hosted model.
