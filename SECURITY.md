# Security

LedgerPay is a portfolio project, not a production payment system: it holds no card data and runs
against a simulated PSP. This page covers the one kind of real secret it uses, API keys for external
services such as `GEMINI_API_KEY` for the reconciliation assistant (#26), and how they are kept out of
the repository.

## Reporting

Please report a vulnerability through GitHub's private vulnerability reporting on this repository,
not in a public issue.

## Secrets

**Where they live.** In a file outside the repository, readable by you alone, loaded only into the
command that needs it; or a GitHub Actions secret. For the Gemini key:

```bash
mkdir -p ~/.config/ledgerpay
printf 'export GEMINI_API_KEY=%s\n' 'AQ.…' > ~/.config/ledgerpay/gemini.env   # or edit it by hand
chmod 600 ~/.config/ledgerpay/gemini.env
scripts/with-gemini.sh ./mvnw …     # the key is set for this command only
```

`scripts/with-gemini.sh` refuses a file inside the repository or readable by others, and passes on
the key alone. Exporting it from `~/.bashrc` would hand it to every process started from a shell:
build plugins, package install scripts and editor extensions inherit the environment. Never in a tracked file: not `application.yml`, not `docker-compose.yml`, not a
`.env` file (`.env`, `.env.*`, `*.env` and `.envrc` are git-ignored), and not baked into an image:
the Dockerfile takes no `ARG` or `ENV` for them, and `.dockerignore` keeps the same files out of
the build context, which the build stage copies whole.

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
   [gitleaks](https://github.com/gitleaks/gitleaks), through Docker or a local `gitleaks`, and
   refuses the commit, so a key never enters the local history. It fails closed: with neither
   scanner available, or a scan that did not run, the commit is refused.
   `LEDGERPAY_SKIP_SECRET_SCAN=1` skips it on purpose for one command. Enable it once per clone:

   ```bash
   git config core.hooksPath scripts/git-hooks
   ```

2. **GitHub push protection**: on for this repository. It refuses a push with a key in a format
   it recognises, the classic `AIza…` Google API key among them, but **not** AI Studio's `AQ.…`
   keys: a test push of a fake one went through (2026-10-26). For those keys the hook is the only
   check before publication.
3. **CI** (`secrets` job): scans every commit of the branch with the same `.gitleaks.toml`, so a key
   committed and then deleted is still found. It runs after the push: in a public repository the
   key is already published by then, and must be revoked.

Google issues Gemini keys in two formats, and both are found anywhere, a bare string included: the
classic `AIza…` by gitleaks' default rule `gcp-api-key`, and Google AI Studio's newer `AQ.…` by the
rule `google-ai-studio-key` in `.gitleaks.toml`, which no default rule covers (the generic one only
finds it next to a word like "key"). A false positive that is public by design goes in the allowlist in
`.gitleaks.toml`; never bypass the hook with `--no-verify` to commit a real key.

## If a key leaks

1. **Revoke it first**, then create a new one (for Gemini: delete it in Google AI Studio). Rewriting git
   history does not help once a key has been pushed or shared: copies may already exist.
2. Put the new key in your environment only.
3. Find where it went: `git log -p --all | grep -nE 'AIza|AQ\.'`, the CI logs, and any chat or issue it was
   pasted into.

## Limiting the damage

For a Gemini key from Google AI Studio:

- Restrict the key to the **Generative Language API** in the Google Cloud console (APIs & Services →
  Credentials), so a leaked key can call nothing else.
- Keep billing **off** on its Google Cloud project. On the free tier a leaked key can use up the
  quota, but cannot run up charges.
- The free tier allows Google to use prompts and responses to improve its products. Only synthetic
  demo data is sent; a real deployment would need a paid tier or a self-hosted model.
