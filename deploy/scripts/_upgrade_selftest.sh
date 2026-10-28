#!/usr/bin/env bash
# Run deployment entry points against a fake Docker command. No daemon or database is used.
set -eu
SOURCE="$(cd "$(dirname "$0")/../.." && pwd)"
TEMP_TEST="$(mktemp -d -t codereview-deploy-test.XXXXXX)"
case "$TEMP_TEST" in /*codereview-deploy-test.*) ;; *) exit 1 ;; esac
trap 'rm -rf -- "$TEMP_TEST"' EXIT
export DOCKER_TEST_LOG MODE
docker() {
  printf '%s\n' "$*" >> "$DOCKER_TEST_LOG"
  case "$1" in
    inspect)
      if [ "$2" = "--format" ]; then
        if [ "$MODE" = running ]; then echo true; else echo false; fi
      elif [ "${!#}" = codereview-app ] && [ "$MODE" = health-failure ]; then
        echo unhealthy
      else echo healthy; fi ;;
    run) [ "$MODE" != config-failure ] ;;
    network)
      if [ "$2" = ls ]; then echo bridge; else echo 172.17.0.0/16; fi ;;
    exec)
      if [[ "$*" == *schema_name* ]]; then
        if [[ "$*" == *"schema_name='code_review'"* ]]; then echo 0; else echo 1; fi
      elif [[ "$*" == *mysqldump* ]]; then echo 'CREATE TABLE fixture(id INT);'
      else cat >/dev/null; fi ;;
    *) return 0 ;;
  esac
}
export -f docker

for MODE in running config-failure health-failure success; do
  B="$TEMP_TEST/$MODE"
  mkdir -p "$B/scripts" "$B/config" "$B/images" "$B/data/mysql"
  cp "$SOURCE/deploy/scripts/common.sh" "$SOURCE/deploy/scripts/deploy.sh" "$SOURCE/deploy/scripts/rollback.sh" "$B/scripts/"
  printf '#!/bin/bash\nexit 0\n' > "$B/scripts/preflight.sh"
  printf '#!/bin/bash\nexit 0\n' > "$B/scripts/backup.sh"
  cp "$SOURCE/deploy/docker-compose.yml" "$B/"
  cp "$SOURCE/deploy/.env.example" "$B/.env"
  sed -i -e 's/^MYSQL_ROOT_PASSWORD=.*/MYSQL_ROOT_PASSWORD=TestRoot/' \
    -e 's/^AGENT_DB_PASSWORD=.*/AGENT_DB_PASSWORD=TestAgent/' "$B/.env"
  printf 'agent:\n  datasource:\n    url: jdbc:mysql://mysql:3306/ai_code_review_agent\n' > "$B/config/application.yml"
  cp "$B/config/application.yml" "$B/expected.yml"
  printf preserved > "$B/data/mysql/sentinel"
  touch "$B/images/codereview-app-test.tar.gz"
  DOCKER_TEST_LOG="$B/docker.log"
  export MODE DOCKER_TEST_LOG
  if bash "$B/scripts/deploy.sh" --tag test --yes > "$B/output.log" 2>&1; then RESULT=0; else RESULT=$?; fi
  cmp "$B/config/application.yml" "$B/expected.yml"
  [ "$(cat "$B/data/mysql/sentinel")" = preserved ]
  case "$MODE" in
    running) [ "$RESULT" -ne 0 ]; ! grep -q 'load\|run\|compose .*up' "$DOCKER_TEST_LOG" ;;
    config-failure) [ "$RESULT" -ne 0 ]; grep -q -- --check-config "$DOCKER_TEST_LOG"; ! grep -q 'compose .*up' "$DOCKER_TEST_LOG" ;;
    health-failure) [ "$RESULT" -ne 0 ]; grep -q 'compose .*stop app' "$DOCKER_TEST_LOG"; ! grep -q '^APP_TAG=previous' "$B/.env" ;;
    success) [ "$RESULT" = 0 ]; grep -q 'compose .*up -d app' "$DOCKER_TEST_LOG" ;;
  esac
  echo "PASS deployment: $MODE, existing YAML and data retained"
done

B="$TEMP_TEST/success"
if bash "$B/scripts/rollback.sh" --tag test --yes > "$B/rollback.log" 2>&1; then exit 1; fi
grep -q -- --schema-compatible "$B/rollback.log"
echo 'PASS rollback: explicit compatibility acknowledgement required'

cp "$SOURCE/deploy/scripts/backup.sh" "$B/scripts/backup.sh"
bash "$B/scripts/backup.sh" > "$B/backup.log" 2>&1
grep -q '兼容库 code_review 不存在' "$B/backup.log"
gzip -t "$B"/backups/ai_code_review_agent-*.sql.gz
echo 'PASS backup: absent compatibility schema skipped, business dump created'
