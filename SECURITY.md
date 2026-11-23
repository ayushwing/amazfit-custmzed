# Security and privacy

## What this project promises

- **No network access in our code.** The phone app declares no `INTERNET` permission, and the watch app only opens a Bluetooth socket to the phone. Notifications, calls and health data travel watch ↔ phone over Bluetooth and stop there.
- **Huami's network routes are cut.** The watch's upload, FTP, OTA, logger and lab packages are hidden (`scripts/watch-debloat.sh`). Wi-Fi stays off, and the Zepp app is not installed on the phone.
- **Nothing identifying is published.** Serials, Bluetooth addresses, emails and keys never enter this repo. See below.

## Known trade-offs

- **The Bluetooth link uses an insecure RFCOMM socket.** That means no bonding, so the phone can connect without Huami's pairing UI. A nearby device that knows the watch's address and the service UUID could connect while the phone isn't connected. The link only carries notification text and call controls. Moving to a bonded, encrypted socket is planned.
- **The phone app can answer and decline calls** (`ANSWER_PHONE_CALLS`) and read call state (`READ_PHONE_STATE`). It acts only on frames from the connected watch.
- **The test hook `TestReceiver`** is exported but guarded by `android.permission.DUMP`, which only the ADB shell and the system hold.

## Before you commit or push

```bash
git config core.hooksPath .githooks     # once per clone: scan runs on every push
scripts/scan-secrets.sh --staged        # before a commit
scripts/scan-secrets.sh                 # full history, same as the hook
```

Put your own identifiers in `.scan-private` (gitignored, one regex per line). Details are in `.claude/skills/security-scan/SKILL.md`.

## Reporting

Open a GitHub issue without sensitive details and ask for a private channel.
