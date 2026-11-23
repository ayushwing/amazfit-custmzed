---
name: security-scan
description: Check this repo for confidential data before anything is committed, pushed or published. Use before every git commit and push, after adding logs, screenshots, configs or test output, and whenever device serials, Bluetooth addresses, emails, IPs or keys might have been pasted into a file.
---

# Security scan before publishing

This repo is public. The project works with real hardware over ADB, so device
identifiers and personal data turn up in logs and command output all the time.
Nothing below may reach GitHub.

## Run it

```bash
scripts/scan-secrets.sh --staged   # before committing: only what is staged
scripts/scan-secrets.sh            # before pushing: every commit on HEAD and origin/main
```

Exit 0 means clean. Any `FAIL` must be fixed first. `WARN` lines are reported for a human to decide.

The pre-push hook runs the full scan automatically once enabled:

```bash
git config core.hooksPath .githooks
```

## What it catches

- Bluetooth/Wi-Fi MAC addresses, emails, private IPs, home-directory paths, phone numbers
- Private keys, GitHub/AWS/OpenAI/Google/Slack tokens, and passwords or API keys assigned in config or code
- Files that must never be committed: `*.apk`, `*.jks`, `*.keystore`, `*.idsig`, `local.properties`, `google-services.json`, `.env`, `.scan-private`
- Commit messages containing any of the above; public author emails (warning)
- Everything listed in `.scan-private`

## `.scan-private`

Your own identifiers live in `.scan-private` at the repo root, one extended
regex per line: watch and phone serials, Bluetooth MACs, the watch's Bluetooth
name suffix, your email, your home network prefix. The file is gitignored, so
the list of things to hide is never itself published. If it is missing, the
scan warns and only the generic checks run. When a new device or identifier
shows up (`adb devices`, `settings get secure bluetooth_address`), add it.

## Rules for writing files in this repo

- Never paste raw `adb devices`, `dumpsys` or `logcat` output into docs. Redact serials and MACs to `<watch-serial>` / `<watch-bt-mac>`.
- Scripts find devices by model (`adb devices -l | awk '/model:Amazfit/'`), never by hard-coded serial.
- The watch address lives in the phone app's private prefs, set at runtime, never in source.
- Screenshots go in `build/` (ignored). Check them for names, numbers and message contents before adding any to `docs/`.

## If something was already pushed

1. Remove it from the working tree and commit.
2. Rewrite history so no commit contains it (`git filter-branch --tree-filter` or `git filter-repo --replace-text`), keeping a backup branch.
3. Re-run `scripts/scan-secrets.sh` until clean, then force-push with the user's explicit go-ahead.
4. Treat any leaked key or token as compromised: revoke and rotate it. Rewriting history does not un-publish it.
