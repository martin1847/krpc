#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_CMD="${GRADLE_CMD:-gradle}"
API_BASE="${CENTRAL_API_BASE:-https://central.sonatype.com/api/v1/publisher}"
PUBLISHING_TYPE="${CENTRAL_PUBLISHING_TYPE:-USER_MANAGED}"
STATE_FILE="${CENTRAL_STATE_FILE:-$ROOT_DIR/build/central-deployment-id}"
DEPLOYMENT_ID=""
ASSUME_YES="false"
RELEASE_BRANCH="${RELEASE_BRANCH:-dev}"

usage() {
  cat <<'USAGE'
Usage:
  gradle/publish-central.sh preflight
  gradle/publish-central.sh bundle
  gradle/publish-central.sh upload
  gradle/publish-central.sh status [deployment-id]
  gradle/publish-central.sh publish [deployment-id] --yes

Commands:
  preflight Assert the checkout is safe to publish from (clean tree, on the
            release branch, no rebase/merge in progress). Runs automatically
            before bundle/upload; runnable standalone for testing.
  bundle    Build, sign, stage, and zip Central bundle locally.
  upload    Build bundle, upload as USER_MANAGED, and wait for VALIDATED.
  status    Print Central deployment status JSON. Defaults to last uploaded deployment.
  publish   Publish a VALIDATED deployment to Maven Central. Requires --yes.

Environment:
  GRADLE_CMD                 Gradle command to use. Default: gradle
  OSSRH_BEARER_TOKEN         Central Portal bearer token. If missing, read from Gradle properties.
  CENTRAL_PUBLISHING_TYPE    Upload publishing type. Default: USER_MANAGED
  CENTRAL_STATE_FILE         File that stores last deployment id. Default: build/central-deployment-id
  RELEASE_BRANCH              Branch preflight checks HEAD against. Default: dev
USAGE
}

die() {
  echo "error: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

# preflight guards against two real incidents (shared-checkout-single-writer):
# 1) an orchestrator ran publishToMavenLocal on a checkout a worker had mid-edit.
# 2) a release commit landed on the wrong local branch and got published from there.
# Both are caught by: clean tracked tree + HEAD pinned to the exact commit origin
# has for RELEASE_BRANCH (rev comparison, not branch-name comparison, so a
# detached HEAD sitting on the right commit still passes).
preflight() {
  local dirty
  dirty="$(git -C "$ROOT_DIR" status --porcelain | grep -v '^??' || true)"
  if [[ -n "$dirty" ]]; then
    die "working tree has uncommitted changes to tracked files — refusing to publish from a dirty checkout (an orchestrator publishing from a worker's in-progress checkout bit us before). Commit or stash first. Dirty files:
$dirty"
  fi

  local git_dir
  git_dir="$(git -C "$ROOT_DIR" rev-parse --git-dir)"
  if [[ -d "$git_dir/rebase-merge" || -d "$git_dir/rebase-apply" || -f "$git_dir/MERGE_HEAD" ]]; then
    die "a rebase or merge is in progress in this checkout — finish or abort it before publishing"
  fi

  git -C "$ROOT_DIR" fetch origin >&2

  local head_rev origin_rev
  head_rev="$(git -C "$ROOT_DIR" rev-parse HEAD)"
  origin_rev="$(git -C "$ROOT_DIR" rev-parse "origin/${RELEASE_BRANCH}" 2>/dev/null)" \
    || die "origin/${RELEASE_BRANCH} not found — fetch failed or RELEASE_BRANCH is wrong"

  if [[ "$head_rev" != "$origin_rev" ]]; then
    die "HEAD (${head_rev}) does not match origin/${RELEASE_BRANCH} (${origin_rev}) — refusing to publish from an unsynced or wrong-branch checkout (a release commit landing on the wrong local branch bit us before). If you really mean to publish from a different branch, override explicitly: RELEASE_BRANCH=<branch>"
  fi

  echo "preflight ok: clean tree, HEAD == origin/${RELEASE_BRANCH} (${head_rev})" >&2
}

project_version() {
  "$GRADLE_CMD" -q properties | awk -F': ' '/^version:/ {print $2; exit}'
}

project_group() {
  "$GRADLE_CMD" -q properties | awk -F': ' '/^group:/ {print $2; exit}'
}

project_name() {
  "$GRADLE_CMD" -q properties | awk -F': ' '/^name:/ {print $2; exit}'
}

central_token() {
  if [[ -n "${OSSRH_BEARER_TOKEN:-}" ]]; then
    printf '%s' "$OSSRH_BEARER_TOKEN"
    return
  fi

  "$GRADLE_CMD" -q properties | awk -F': ' '/^OSSRH_BEARER_TOKEN:/ {print $2; exit}'
}

status_json() {
  local token="$1"
  local deployment_id="$2"

  curl --request POST \
    --silent \
    --show-error \
    --fail-with-body \
    --header "Authorization: Bearer ${token}" \
    "${API_BASE}/status?id=${deployment_id}"
}

deployment_state() {
  sed -n 's/.*"deploymentState":"\([^"]*\)".*/\1/p'
}

save_deployment_id() {
  mkdir -p "$(dirname "$STATE_FILE")"
  printf '%s\n' "$1" > "$STATE_FILE"
}

last_deployment_id() {
  [[ -f "$STATE_FILE" ]] || return 1
  sed -n '1p' "$STATE_FILE"
}

resolve_deployment_id() {
  if [[ -n "$DEPLOYMENT_ID" ]]; then
    return
  fi

  DEPLOYMENT_ID="$(last_deployment_id || true)"
  [[ -n "$DEPLOYMENT_ID" ]] || die "deployment id is required; run upload first or pass an id"
}

wait_for_state() {
  local token="$1"
  local deployment_id="$2"
  local json=""
  local state=""

  for _ in {1..60}; do
    json="$(status_json "$token" "$deployment_id")"
    echo "$json"
    state="$(printf '%s' "$json" | deployment_state)"

    case "$state" in
      VALIDATED|PUBLISHED)
        return 0
        ;;
      FAILED)
        return 1
        ;;
    esac

    sleep 10
  done

  die "timed out waiting for deployment ${deployment_id}"
}

build_bundle() {
  local project_name="$1"
  local version="$2"
  local bundle_dir="$ROOT_DIR/build/central-bundle"
  local bundle_zip="$ROOT_DIR/build/${project_name}-${version}-central-bundle.zip"

  # Exclude the quickstart example from the release build: it is NOT a published module, and its
  # Quarkus app-model resolution transitively wants the exact krpc rpc-* version that ext-rpc pins
  # (e.g. ext-rpc 1.0.3 -> rpc-client 1.0.3) which may not be in the local mirror when this build
  # is ahead of that pin. quickstart is validated separately by the native-smoke CI, so skipping it
  # here does not reduce coverage; the 9 published modules still build+sign+stage.
  "$GRADLE_CMD" clean build publishAllPublicationsToCentralStagingRepository -x test -x :examples:quickstart:build >&2

  rm -rf "$bundle_dir" "$bundle_zip"
  mkdir -p "$bundle_dir"

  shopt -s nullglob
  local staging_dirs=("$ROOT_DIR"/*/build/central-staging)
  shopt -u nullglob

  [[ "${#staging_dirs[@]}" -gt 0 ]] || die "no module central-staging directories found"

  for staging_dir in "${staging_dirs[@]}"; do
    # ext-rpc-gen releases on its own cycle (SPEC §12) and pins its own version —
    # bundling it re-uploads an existing GAV and Central rejects the whole bundle
    # (bit us on the 1.0.0 release). Never include it in the krpc bundle.
    [[ "$staging_dir" == *"/ext-rpc-gen/"* ]] && continue
    cp -R "$staging_dir"/. "$bundle_dir"/
  done

  (
    cd "$bundle_dir"
    zip -qr "$bundle_zip" .
  )

  unzip -t "$bundle_zip" >/dev/null
  echo "$bundle_zip"
}

upload_bundle() {
  local token="$1"
  local bundle_zip="$2"
  local group="$3"
  local project_name="$4"
  local version="$5"
  local name="${group}-${project_name}-${version}"

  curl --request POST \
    --silent \
    --show-error \
    --fail-with-body \
    --header "Authorization: Bearer ${token}" \
    --form "bundle=@${bundle_zip}" \
    "${API_BASE}/upload?name=${name}&publishingType=${PUBLISHING_TYPE}"
}

publish_deployment() {
  local token="$1"
  local deployment_id="$2"

  curl --request POST \
    --silent \
    --show-error \
    --fail-with-body \
    --write-out '%{http_code}' \
    --output /tmp/central-publish-response.txt \
    --header "Authorization: Bearer ${token}" \
    "${API_BASE}/deployment/${deployment_id}"
}

parse_args() {
  COMMAND="${1:-}"
  shift || true

  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --yes|-y)
        ASSUME_YES="true"
        ;;
      -*)
        die "unknown option: $1"
        ;;
      *)
        if [[ -z "$DEPLOYMENT_ID" ]]; then
          DEPLOYMENT_ID="$1"
        else
          die "unexpected argument: $1"
        fi
        ;;
    esac
    shift
  done
}

main() {
  parse_args "$@"

  case "$COMMAND" in
    ""|help|--help|-h)
      usage
      ;;
    preflight|bundle|upload|status|publish)
      ;;
    *)
      usage
      die "unknown command: $COMMAND"
      ;;
  esac

  require_command "$GRADLE_CMD"
  require_command curl

  cd "$ROOT_DIR"

  local token=""
  local version=""
  local group=""
  local project_name=""
  local bundle_zip=""

  case "$COMMAND" in
    preflight)
      preflight
      ;;
    bundle)
      preflight
      version="$(project_version)"
      project_name="$(project_name)"
      build_bundle "$project_name" "$version"
      ;;
    upload)
      preflight
      token="$(central_token)"
      [[ -n "$token" ]] || die "missing Central token"
      version="$(project_version)"
      group="$(project_group)"
      project_name="$(project_name)"
      bundle_zip="$(build_bundle "$project_name" "$version")"
      DEPLOYMENT_ID="$(upload_bundle "$token" "$bundle_zip" "$group" "$project_name" "$version")"
      save_deployment_id "$DEPLOYMENT_ID"
      echo "deploymentId=${DEPLOYMENT_ID}"
      wait_for_state "$token" "$DEPLOYMENT_ID" >/dev/null
      status_json "$token" "$DEPLOYMENT_ID"
      ;;
    status)
      resolve_deployment_id
      token="$(central_token)"
      [[ -n "$token" ]] || die "missing Central token"
      status_json "$token" "$DEPLOYMENT_ID"
      ;;
    publish)
      resolve_deployment_id
      [[ "$ASSUME_YES" == "true" ]] || die "publish is irreversible; pass --yes"
      token="$(central_token)"
      [[ -n "$token" ]] || die "missing Central token"
      publish_deployment "$token" "$DEPLOYMENT_ID"
      echo
      ;;
  esac
}

main "$@"
