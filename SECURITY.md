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
# bash: typed at a hidden prompt, so the key is in no command line and no shell history. Empty
# input leaves a working file alone. umask 077 covers a new file, chmod 600 one that already was
# readable by others. (zsh's read differs: there, use read -rs 'k?Gemini key: '.)
(umask 077; read -rsp 'Gemini key: ' k; echo
 [[ -n $k ]] || { echo 'no key typed; nothing changed' >&2; exit 1; }
 printf 'export GEMINI_API_KEY=%s\n' "$k" > ~/.config/ledgerpay/gemini.env
 chmod 600 ~/.config/ledgerpay/gemini.env)
scripts/with-gemini.sh ./mvnw …     # the key is set for this command only
```

Never put the key itself on a command line, `printf '…AQ.…'` or `export GEMINI_API_KEY=AQ.…`
included: the shell writes it to `~/.bash_history`. If one already did, delete that line with every
other terminal closed (an open shell writes its history back when it exits).

`scripts/with-gemini.sh` refuses a file inside the repository or readable by others, and passes on
the key alone. Exporting it from `~/.bashrc` would hand it to every process started from a shell:
build plugins, package install scripts and editor extensions inherit the environment.

Never in a tracked file: not `application.yml`, not `docker-compose.yml`, not a
`.env` file (`.env`, `.env.*`, `*.env` and `.envrc` are git-ignored), and not baked into an image:
the Dockerfile takes no `ARG` or `ENV` for them, and `.dockerignore` keeps the same files out of
the build context, which the build stage copies whole.

The rules for any service that takes one (the first is the assistant, #26):

- Configuration reads it as a placeholder with an empty default, for example `${GEMINI_API_KEY:}`.
  Without one the feature that needs it is off, and everything else starts.
- Compose takes it from your shell as a secret, a file under `/run/secrets` that the service reads
  (`GEMINI_API_KEY_FILE`), not as an environment variable, which `docker inspect` and
  `docker compose config` would print. `infra/docker-compose.assistant.yml` does this, and
  `scripts/lib.sh` adds it only when `GEMINI_API_KEY` is set.
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

Four layers, each catching what the one before missed:

1. **Pre-commit hook** (`scripts/git-hooks/pre-commit`): scans the staged changes with
   [gitleaks](https://github.com/gitleaks/gitleaks), through Docker or a local `gitleaks`, and
   refuses the commit, so a key never enters the local history. It fails closed: with neither
   scanner available, or a scan that did not run, the commit is refused.
   `LEDGERPAY_SKIP_SECRET_SCAN=1` skips it on purpose for one command. Enable the hooks once per
   clone:

   ```bash
   git config core.hooksPath "$(git rev-parse --show-toplevel)/scripts/git-hooks"
   ```

   The path is absolute on purpose. A relative `scripts/git-hooks` is looked up in the working tree
   that runs the hook, so a git worktree checked out at an older commit runs that commit's hooks, or
   none: a test push from a worktree on a commit before the pre-push hook existed published a fake
   key. With the absolute path every worktree uses the main checkout's hooks, and the scan reads its
   rules from there too, so a worktree on an old commit cannot scan with old rules. The one gap
   left: if the main checkout itself is on a commit from before a hook existed, git skips that hook
   without a word.

2. **Pre-push hook** (`scripts/git-hooks/pre-push`): scans every commit the push would publish
   (what no remote-tracking branch has yet) and refuses the push, with the same scanner and the
   same fail-closed rule. It catches what pre-commit never saw: commits made before the hooks were
   enabled in a clone, with `--no-verify`, or by a rebase or cherry-pick.
3. **GitHub push protection**: on for this repository. It refuses a push with a key in a format
   it recognises, the classic `AIza…` Google API key among them, but **not** AI Studio's `AQ.…`
   keys: a test push of a fake one went through (2026-10-26). For those keys the two hooks are the
   only checks before publication, and a commit made in GitHub's web editor bypasses both.
4. **CI** (`secrets` job): scans every commit of the branch with the same `.gitleaks.toml`, so a key
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
