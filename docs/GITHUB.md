# Git & GitHub

## Local Git (offline)

Git panel → **Init Git** creates a repository (branch `main`) and a secret-safe `.gitignore`. The panel has four tabs: **Changes**, **Staged**, **History** and **Branches**. Tap a file to see its diff; use the checkbox to stage or unstage it. Enter a message (or tap **AI message**) and **Commit**. Commits are scanned for secrets first. If any are found, you can unstage the files and ignore them, or explicitly commit anyway.

Set the author name and email in Settings → Git.

## Connecting GitHub

- **Personal access token:** Git panel → Connect GitHub → paste a token with the `repo` and `workflow` scopes. The token is verified against `GET /user` and stored encrypted.
- **OAuth device login:** register a GitHub OAuth App with device flow enabled, put its client ID in Settings → Git, then choose "sign in with device code". FOLD FORGE shows the code and verification URL and polls until you approve.

## Remote workflows

- **Clone:** Home → Clone GitHub → `owner/repo` or an https URL. FOLD FORGE detects the project type and entry, and creates `.foldforge` metadata.
- **Create repository:** Git panel → Create repo (name, private/public, description, optional README). The new repo is set as `origin`.
- **Push / Pull / Fetch** use HTTPS with your token, which is only held in memory during the transfer. A snapshot is taken before every pull.
- **Conflicts:** conflicting files are listed with ours/theirs hunks. You can resolve with *Use ours / Use theirs / Keep both*, edit manually, or *Ask AI to explain*. *Abort merge* resets to the last commit.
- **Open repository page** opens the repo in the browser.

## GitHub Actions

Android projects can include `.github/workflows/android.yml` (added by the exporter or by Build → Add CI workflow). The workflow runs `test`, `lint` and `assembleDebug`, then uploads the APK. FOLD FORGE can dispatch it and download the result (see APK_BUILD.md).
