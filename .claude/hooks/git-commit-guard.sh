#!/bin/sh
# Block agent attempts to skip .githooks on git commit, see .claude/rules/git-commit.md
cmd=$(jq -r '.tool_input.command // empty')

# Drop heredoc bodies and quoted strings so flags inside the message are ignored
flags=$(printf '%s\n' "$cmd" | awk '
  skip { if ($0 == tag) skip = 0; next }
  { if (match($0, /<<-?[[:space:]]*["\047]?[A-Za-z_]+/)) { t = substr($0, RSTART, RLENGTH); gsub(/<<-?[[:space:]]*["\047]?/, "", t); tag = t; skip = 1 } print }
' | perl -0777 -pe 's/\x27[^\x27]*\x27//g; s/"(?:\\.|[^"\\])*"//gs')

# Keep only the shell segments that run git commit
flags=$(printf '%s\n' "$flags" | tr ';&|' '\n\n\n' | grep -E '(^|[^[:alnum:]_-])git([[:space:]]+-[^[:space:]]+([[:space:]]+[^-[:space:]][^[:space:]]*)?)*[[:space:]]+commit([[:space:]]|$)') || exit 0

deny() {
  echo "Blocked: $1. Commit hooks enforce .claude/rules/git-commit.md; fix the message instead of skipping them." >&2
  exit 2
}

printf '%s' "$flags" | grep -Eq -- '--no-verify|(^|[[:space:]])-[a-zA-Z]*n[a-zA-Z]*([[:space:]]|$)' && deny "--no-verify / -n is not allowed"
printf '%s' "$flags" | grep -Eiq 'core\.hookspath|GIT_CONFIG_(COUNT|KEY|VALUE|PARAMETERS)' && deny "overriding core.hooksPath is not allowed"
exit 0
