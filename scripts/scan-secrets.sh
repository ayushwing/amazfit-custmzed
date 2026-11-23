#!/usr/bin/env bash
# Scan what is (or is about to be) public for confidential data.
#
#   scripts/scan-secrets.sh             every commit reachable from HEAD and origin/main
#   scripts/scan-secrets.sh --staged    only what is staged for the next commit
#
# Checks file contents, file names, commit messages and author metadata for
# device identifiers, personal data, credentials and build artefacts.
#
# Your own identifiers (watch/phone serials, Bluetooth MAC, email, contact
# names, home Wi-Fi name...) go one regex per line in .scan-private at the repo
# root. That file is gitignored, so the list of things to hide is never
# published itself.
#
# Exit code: 0 clean, 1 findings. WARN lines are reported but do not fail.

set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

MODE=history
[[ "${1:-}" == "--staged" ]] && MODE=staged

fail=0
red()  { printf '\033[31m%s\033[0m\n' "$*"; }
note() { printf '%s\n' "$*"; }

# name | regex. Matches listed in ALLOW below are ignored.
PATTERNS=(
  "mac address|([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"
  "email|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}"
  "private ip|(^|[^0-9.])(192\.168|10\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01]))\.[0-9]{1,3}\.[0-9]{1,3}"
  "home path|/Users/[A-Za-z0-9_-]+|/home/[A-Za-z0-9_-]+"
  "phone number|\+[0-9]{1,3}[ -]?[0-9]{9,11}"
  "private key|BEGIN [A-Z ]*PRIVATE KEY"
  "token|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{35}|xox[abp]-[A-Za-z0-9-]{10,}"
  "credential|(password|passwd|secret|api[_-]?key|auth[_-]?token)[\"' ]*[:=][\"' ]*[^\"' ,)]{6,}"
)

# Known-safe matches: placeholders, the public Android debug key, attribution.
ALLOW='AA:BB:CC:DD:EE:FF|<watch-serial>|<watch-bt-mac>|noreply@anthropic\.com|-storepass android|-keypass android|pass:android|android@android\.com'

# Files that must never be committed.
FORBIDDEN='\.(apk|aab|jks|keystore|p12|pem|idsig)$|(^|/)local\.properties$|(^|/)google-services\.json$|(^|/)\.scan-private$|\.env$'

# Your own identifiers, kept out of git.
PRIVATE=()
if [[ -f .scan-private ]]; then
  while IFS= read -r line; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    PRIVATE+=("private|$line")
  done < .scan-private
else
  note "WARN  no .scan-private file: your own serials, MAC and email are not being checked"
fi

grep_tree() { # $1 regex -> "commit:path:line:text" or "path:line:text"
  if [[ $MODE == staged ]]; then
    git grep --cached -nIE "$1" -- . ':!scripts/scan-secrets.sh' 2>/dev/null || true
  else
    local revs
    revs=$(git rev-list HEAD $(git rev-parse -q --verify origin/main >/dev/null && echo origin/main) 2>/dev/null | sort -u)
    # shellcheck disable=SC2086
    git grep -nIE "$1" $revs -- . ':!scripts/scan-secrets.sh' 2>/dev/null | sed -E 's/^([0-9a-f]{7})[0-9a-f]{33}:/\1:/' || true
  fi
}

note "== contents ($MODE)"
for entry in "${PATTERNS[@]}" "${PRIVATE[@]}"; do
  name=${entry%%|*}; rx=${entry#*|}
  hits=$(grep_tree "$rx" | grep -vE "$ALLOW" | sort -u)
  if [[ -n "$hits" ]]; then
    red "FAIL  $name"
    printf '%s\n' "$hits" | head -10 | cut -c1-200 | sed 's/^/      /'
    fail=1
  fi
done

note "== file names"
if [[ $MODE == staged ]]; then
  files=$(git diff --cached --name-only)
else
  files=$(git log HEAD $(git rev-parse -q --verify origin/main >/dev/null && echo origin/main) --name-only --format= | sort -u)
fi
bad=$(printf '%s\n' "$files" | grep -E "$FORBIDDEN" || true)
[[ -n "$bad" ]] && { red "FAIL  forbidden files"; printf '%s\n' "$bad" | sed 's/^/      /'; fail=1; }

if [[ $MODE == history ]]; then
  note "== commit messages and authors"
  range="HEAD $(git rev-parse -q --verify origin/main >/dev/null && echo origin/main)"
  # shellcheck disable=SC2086
  msgs=$(git log $range --format='%h %B' | grep -E "@|([0-9A-Fa-f]{2}:){5}|/Users/" | grep -vE "$ALLOW" || true)
  [[ -n "$msgs" ]] && { red "FAIL  commit messages"; printf '%s\n' "$msgs" | sed 's/^/      /'; fail=1; }
  for entry in "${PRIVATE[@]}"; do
    rx=${entry#*|}
    # shellcheck disable=SC2086
    m=$(git log $range --format='%h %an %cn %B' | grep -E "$rx" | grep -vE "$ALLOW" || true)
    [[ -n "$m" ]] && { red "FAIL  private pattern in commit metadata"; printf '%s\n' "$m" | cut -c1-120 | sed 's/^/      /'; fail=1; }
  done
  # Author emails are public on GitHub. A personal address is a choice, not a leak, so warn.
  # shellcheck disable=SC2086
  git log $range --format='%ae' | sort -u | grep -vE 'noreply' | while read -r e; do
    note "WARN  commit author email is public: $e (use your GitHub noreply address to hide it)"
  done
fi

[[ $fail == 0 ]] && note "CLEAN" || red "Findings above. Fix before pushing; for history already pushed, rewrite it and force-push."
exit $fail
