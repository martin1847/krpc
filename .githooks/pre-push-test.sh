#!/usr/bin/env bash
# Isolated consumer probes for the existing pre-push/selfcheck entry points.
set -euo pipefail
HOOK="$(cd "$(dirname "$0")" && pwd)/pre-push"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# Do not inherit caller repository/configuration into the temporary repositories.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_COMMON_DIR
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export GIT_AUTHOR_NAME='Gate Test' GIT_AUTHOR_EMAIL='gate-test@example.invalid'
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME" GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
PASS=0
FAIL=0
REPOS=0
ZERO=0000000000000000000000000000000000000000

new_repo() {
  REPOS=$((REPOS + 1))
  mkdir "$TMP/$REPOS"
  cd "$TMP/$REPOS"
  git init -q -b integration
  mkdir -p docs-site/nested skills/krpc/references
  printf 'contract\n' > SPEC.md
  cp SPEC.md skills/krpc/references/SPEC.md
  printf "url: 'https://martin1847.github.io',\nbaseUrl: '/krpc/',\n" > docs-site/docusaurus.config.js
  printf 'guide\n' > docs-site/nested/old.md
  printf 'obsolete\n' > docs-site/obsolete.md
  commit 'initial repository'
  BASE=$(git rev-parse HEAD)
  git update-ref refs/remotes/origin/main "$BASE"
  git update-ref refs/remotes/origin/dev "$BASE"
}

commit() {
  git add -A
  git -c core.hooksPath=/dev/null commit -qm "$1"
}

published_docs() {
  # Published development content includes a rename, a deletion, and an edit.
  git mv docs-site/nested/old.md docs-site/nested/guide.md
  git rm -q docs-site/obsolete.md
  printf "url: 'https://krpc.tech',\nbaseUrl: '/',\n" > docs-site/docusaurus.config.js
  commit 'publish docs on development'
  DEV=$(git rev-parse HEAD)
  git update-ref refs/remotes/origin/dev "$DEV"
}

expect() {
  local label="$1" expected="$2" message="$3" actual output
  shift 3
  if output=$("$@" 2>&1); then actual=0; else actual=$?; fi
  if [ "$actual" = "$expected" ] && { [ -z "$message" ] || [[ "$output" == *"$message"* ]]; }; then
    PASS=$((PASS + 1))
    printf 'PASS: %s (rc=%s)\n' "$label" "$actual"
    if [ -n "$message" ]; then printf '%s\n' "$output"; fi
  else
    FAIL=$((FAIL + 1))
    printf 'FAIL: %s (expected rc=%s, got rc=%s; message=%s)\n%s\n' "$label" "$expected" "$actual" "$message" "$output" >&2
    if [[ "$label" == main-only* ]] && [ "$actual" = 0 ]; then
      printf 'KILL: main-only docs edit accepted; restore strict rejection.\n' >&2
      exit 1
    fi
  fi
}

new_repo
published_docs
expect 'published dev content integrates to main (rename/deletion included)' 0 '' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main
# The proposed SHA, not the current checkout, is the local hook's tip.
git checkout -q --detach "$BASE"
expect 'stdin hook compares proposed SHA rather than HEAD' 0 '' \
  bash "$HOOK" origin unused <<EOF
refs/heads/integration $DEV refs/heads/main $BASE
EOF
expect 'stdin new-main hook uses existing baseline semantics' 0 '' \
  bash "$HOOK" origin unused <<EOF
refs/heads/integration $DEV refs/heads/main $ZERO
EOF

new_repo
published_docs
git checkout -qb main-base "$BASE"
printf 'base-only non-site content\n' > README.md
commit 'main advanced outside docs-site'
git update-ref refs/remotes/origin/main HEAD
git -c core.hooksPath=/dev/null merge -q --no-edit "$DEV"
git update-ref refs/pull/1/merge HEAD
git checkout -q --detach refs/pull/1/merge
# Exercise the workflow's explicit ref supply against a local origin, no network.
git init --bare -q "$TMP/origin.git"
git -C "$TMP/origin.git" fetch -q "$PWD" "$DEV:refs/heads/dev"
git remote add origin "$TMP/origin.git"
git update-ref -d refs/remotes/origin/dev
git fetch --no-tags origin '+refs/heads/dev:refs/remotes/origin/dev'
expect 'CI detached PR merge ref preserves published site content' 0 '' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main

new_repo
published_docs
git checkout -qb main-base "$BASE"
printf 'unpublished base-only site page\n' > docs-site/base-only.md
commit 'main has unpublished site content'
git update-ref refs/remotes/origin/main HEAD
git -c core.hooksPath=/dev/null merge -q --no-edit "$DEV"
git update-ref refs/pull/2/merge HEAD
git checkout -q --detach refs/pull/2/merge
expect 'main-only base content in CI merge ref is rejected' 1 'BLOCKED' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main

new_repo
# Independent bad sample: 2026-07-21 incident, cbe62e2 on main, then 4daa1e1
# on dev. Reproduce that url/baseUrl correction BEFORE it reaches origin/dev.
printf "url: 'https://krpc.tech',\nbaseUrl: '/',\n" > docs-site/docusaurus.config.js
commit 'incident: root baseUrl fix only on main'
git branch -m dev
expect 'main-only baseUrl incident rejected even with source named dev' 1 'BLOCKED' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main

new_repo
printf 'unpublished\n' > docs-site/nested/main-only.md
commit 'main-only addition'
expect 'main-only nested page addition rejected' 1 'BLOCKED' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main
expect 'stdin main-only addition rejected' 1 'BLOCKED' \
  bash "$HOOK" origin unused <<EOF
refs/heads/dev $(git rev-parse HEAD) refs/heads/main $BASE
EOF

new_repo
git rm -q docs-site/obsolete.md
commit 'main-only deletion'
expect 'main-only deletion rejected' 1 'BLOCKED' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main

new_repo
git mv docs-site/nested/old.md docs-site/nested/main.md
commit 'main-only rename'
expect 'main-only rename rejected' 1 'BLOCKED' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main

new_repo
published_docs
git update-ref -d refs/remotes/origin/dev
expect 'missing trusted development ref errors' 1 'ERROR' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main
expect 'stdin new-main missing baseline errors instead of skipping' 1 'ERROR' \
  bash "$HOOK" origin unused <<EOF
refs/heads/integration $DEV refs/heads/main $ZERO
EOF
expect 'main without site changes still requires trusted development ref' 1 'ERROR' \
  bash "$HOOK" --range HEAD..HEAD refs/heads/main
expect 'ordinary dev target needs no development comparison' 0 '' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/dev
expect 'other targets retain existing behavior' 0 '' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/release/example
# A branch spelled origin/dev must not replace the trusted remote-tracking ref.
git branch origin/dev HEAD
expect 'local branch cannot impersonate trusted development ref' 1 'ERROR' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main

new_repo
published_docs
git update-ref refs/remotes/origin/dev "$(git rev-parse HEAD:SPEC.md)"
expect 'non-commit development ref errors' 1 'ERROR' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main

new_repo
expect 'invalid range errors rather than silently skipping' 1 'ERROR' \
  bash "$HOOK" --range missing-base..HEAD refs/heads/main
expect 'invalid proposed tip errors' 1 'ERROR' \
  bash "$HOOK" --range origin/main..missing-tip refs/heads/main
expect 'unchanged docs preserve diff-scoped behavior' 0 '' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/main

new_repo
printf 'changed contract\n' >> SPEC.md
commit 'mirror drift'
expect 'SPEC mirror drift still blocked' 1 'byte-identical mirror pair' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/dev

new_repo
git rm -q skills/krpc/references/SPEC.md
commit 'missing SPEC mirror'
expect 'missing SPEC still blocked' 1 'pair is incomplete' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/dev

new_repo
printf '%s\n' {1..1011} > SPEC.md
cp SPEC.md skills/krpc/references/SPEC.md
commit 'overlong mirrored SPEC'
expect 'SPEC line cap still blocked' 1 'over the 1010-line cap' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/dev

new_repo
printf 'updated contract\n' > SPEC.md
cp SPEC.md skills/krpc/references/SPEC.md
commit 'valid mirrored SPEC update'
expect 'valid SPEC update still passes' 0 '' \
  bash "$HOOK" --range origin/main..HEAD refs/heads/dev

printf 'Gate regression suite: %s passed, %s failed\n' "$PASS" "$FAIL"
[ "$FAIL" = 0 ]
