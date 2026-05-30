#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_CMD="${GRADLE_CMD:-gradle}"
API_BASE="${CENTRAL_API_BASE:-https://central.sonatype.com/api/v1/publisher}"
PUBLISHING_TYPE="${CENTRAL_PUBLISHING_TYPE:-USER_MANAGED}"
STATE_FILE="${CENTRAL_STATE_FILE:-$ROOT_DIR/build/central-deployment-id}"
DEPLOYMENT_ID=""
ASSUME_YES="false"

usage() {
  cat <<'USAGE'
Usage:
  gradle/publish-central.sh bundle
  gradle/publish-central.sh upload
  gradle/publish-central.sh status [deployment-id]
  gradle/publish-central.sh publish [deployment-id] --yes

Commands:
  bundle    Build, sign, stage, and zip Central bundle locally.
  upload    Build bundle, upload as USER_MANAGED, and wait for VALIDATED.
  status    Print Central deployment status JSON. Defaults to last uploaded deployment.
  publish   Publish a VALIDATED deployment to Maven Central. Requires --yes.

Environment:
  GRADLE_CMD                 Gradle command to use. Default: gradle
  OSSRH_BEARER_TOKEN         Central Portal bearer token. If missing, read from Gradle properties.
  CENTRAL_PUBLISHING_TYPE    Upload publishing type. Default: USER_MANAGED
  CENTRAL_STATE_FILE         File that stores last deployment id. Default: build/central-deployment-id
USAGE
}

die() {
  echo "error: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
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

  "$GRADLE_CMD" clean build publishAllPublicationsToCentralStagingRepository >&2

  rm -rf "$bundle_dir" "$bundle_zip"
  mkdir -p "$bundle_dir"

  shopt -s nullglob
  local staging_dirs=("$ROOT_DIR"/*/build/central-staging)
  shopt -u nullglob

  [[ "${#staging_dirs[@]}" -gt 0 ]] || die "no module central-staging directories found"

  for staging_dir in "${staging_dirs[@]}"; do
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
    bundle|upload|status|publish)
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
    bundle)
      version="$(project_version)"
      project_name="$(project_name)"
      build_bundle "$project_name" "$version"
      ;;
    upload)
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
