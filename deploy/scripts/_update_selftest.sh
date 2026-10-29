#!/usr/bin/env bash
# 用文件和 Docker 替身验证自动升级，不访问 Docker 守护进程或真实数据库。
set -euo pipefail
SOURCE="$(cd -- "$(dirname -- "$0")/../.." && pwd -P)"
TEST_ROOT="$(mktemp -d -t codereview-update-test.XXXXXX)"
case "$TEST_ROOT" in /*codereview-update-test.*) ;; *) exit 1 ;; esac
trap 'rm -rf -- "$TEST_ROOT"' EXIT
export TEST_INSTALL TEST_CASE TEST_LOG TEST_STATE TEST_QUERIES
docker() {
  printf '%s\n' "$*" >> "$TEST_LOG"
  case "$1" in
    info|version|load|tag) return 0 ;;
    stop) printf stopped > "$TEST_STATE" ;;
    image)
      if [ "$2" = save ]; then
        [ "$TEST_CASE" != export-failure ] || return 1
        printf old-image
      elif { [ "$TEST_CASE" = missing-tag ] || [ "$TEST_CASE" = check-only-missing ]; } && [[ "$*" == *codereview-app:test* ]]; then
        return 1
      elif [[ "$*" == *'{{.Id}}'* ]]; then printf 'sha256:new\n'; fi ;;
    inspect)
      case "$*" in
        *'/var/lib/mysql'*)
          if [ "$TEST_CASE" = wrong-mount ]; then printf 'bind:/wrong/data\n'
          else printf 'bind:%s/data/mysql\n' "$TEST_INSTALL"; fi ;;
        *'/app/config/application.yml'*) printf 'bind:%s/config/application.yml\n' "$TEST_INSTALL" ;;
        *'{{.Config.Image}}'*) printf 'codereview-app:old\n' ;;
        *'{{.Image}}'*)
          if [ "$(cat "$TEST_STATE")" = deployed ]; then
            if [ "$TEST_CASE" = wrong-image ]; then echo sha256:wrong; else echo sha256:new; fi
          else echo sha256:old; fi ;;
        *'{{.State.Running}}'*) [ "$(cat "$TEST_STATE")" = running ] && echo true || echo false ;;
        *)
          if [ "$TEST_CASE" = health-failure ] && [ "${!#}" = codereview-app ]; then echo unhealthy
          else echo healthy; fi ;;
      esac ;;
    run) [ "$TEST_CASE" != config-failure ] ;;
    compose)
      if [[ "$*" == *'stop app'* ]]; then printf stopped > "$TEST_STATE"
      elif [[ "$*" == *'up -d app'* ]]; then
        [ "${APP_TAG:-}" = test ] || return 1
        printf deployed > "$TEST_STATE"
      elif [[ "$*" == *' config'* ]] && [ "$TEST_CASE" = compose-failure ]; then return 1; fi ;;
    network)
      if [ "$2" = ls ]; then echo bridge; else echo 172.17.0.0/16; fi ;;
    exec)
      if [[ "$*" == *mysqldump* ]]; then
        [ "$TEST_CASE" != backup-failure ] || return 1
        echo 'CREATE TABLE preserved(id INT);'
      elif [[ "$*" == *'schema_name='* ]]; then
        if [[ "$*" == *agent_schema_identity* ]]; then
          if [ "$TEST_CASE" = wrong-schema ]; then echo 0; else echo 1; fi
        elif [[ "$*" == *"schema_name='code_review'"* ]]; then echo 0
        else echo 1; fi
      elif [[ "$*" == *'finished_at IS NULL'* ]]; then
        local count
        count="$(cat "$TEST_QUERIES")"; count=$((count+1)); printf '%s' "$count" > "$TEST_QUERIES"
        if [ "$TEST_CASE" = query-failure ]; then return 1
        elif [ "$TEST_CASE" = active ] || { [ "$TEST_CASE" = race ] && [ "$count" -ge 3 ]; }; then echo 1
        else echo 0; fi
      elif [[ "$*" == *'COUNT(*) FROM project'* ]]; then
        if [ "$TEST_CASE" = count-change ] && [ "$(cat "$TEST_STATE")" = deployed ]; then printf '2\t1\t1\t1\t1\n'
        else printf '1\t1\t1\t1\t1\n'; fi
      elif [[ "$*" == *agent_schema_history* ]]; then
        if [ "$TEST_CASE" = migration-failure ]; then echo 1; else echo 0; fi
      elif [[ "$*" == *curl* ]]; then [ "$TEST_CASE" != api-failure ]
      else cat >/dev/null; fi ;;
    *) return 1 ;;
  esac
}
export -f docker
passed=0
run_case() {
  TEST_CASE="$1"
  local base="$TEST_ROOT/$TEST_CASE" result=0
  local -a args=()
  local fresh="$base/new package"
  TEST_INSTALL="$base/installed system"
  TEST_LOG="$base/docker.log"; TEST_STATE="$base/state"; TEST_QUERIES="$base/queries"
  mkdir -p "$fresh"/{images,scripts,config,docs} "$TEST_INSTALL"/{images,scripts,config,data/mysql,data/git-mirrors}
  cp "$SOURCE/deploy/scripts/"{common,deploy,backup,update}.sh "$fresh/scripts/"
  printf '#!/usr/bin/env bash\n[ "$TEST_CASE" != preflight-failure ]\n' > "$fresh/scripts/preflight.sh"
  cp "$SOURCE/deploy/docker-compose.yml" "$fresh/"
  cp "$SOURCE/deploy/config/application.yml.example" "$fresh/config/"
  cp "$SOURCE/deploy/"{系统运维手册.md,系统部署手册.md,00-先读我.md} "$fresh/"
  echo new-doc > "$fresh/docs/fixture.md"
  cp "$fresh/docker-compose.yml" "$TEST_INSTALL/"
  cp "$SOURCE/deploy/scripts/common.sh" "$TEST_INSTALL/scripts/"
  echo old-script > "$TEST_INSTALL/scripts/original.sh"
  cp "$SOURCE/deploy/.env.example" "$TEST_INSTALL/.env"
  sed -i -e 's/^MYSQL_ROOT_PASSWORD=.*/MYSQL_ROOT_PASSWORD=TestRoot/' \
    -e 's/^AGENT_DB_PASSWORD=.*/AGENT_DB_PASSWORD=TestAgent/' "$TEST_INSTALL/.env"
  printf 'agent:\n  datasource:\n    url: jdbc:mysql://mysql:3306/ai_code_review_agent\n' > "$TEST_INSTALL/config/application.yml"
  cp "$TEST_INSTALL/config/application.yml" "$base/expected.yml"
  cp "$TEST_INSTALL/docker-compose.yml" "$base/expected-compose.yml"
  printf preserved > "$TEST_INSTALL/data/mysql/sentinel"
  printf git-cache > "$TEST_INSTALL/data/git-mirrors/sentinel"
  printf target-image > "$fresh/images/codereview-app-test.tar.gz"
  printf old-image > "$TEST_INSTALL/images/codereview-app-old.tar.gz"
  (cd "$fresh/images" && sha256sum *.tar.gz > SHA256SUMS.txt)
  printf running > "$TEST_STATE"; printf 0 > "$TEST_QUERIES"; : > "$TEST_LOG"
  case "$TEST_CASE" in
    checksum) echo changed >> "$fresh/images/codereview-app-test.tar.gz" ;;
    same-path) args=(--new "$TEST_INSTALL") ;;
    nested-path) mkdir "$TEST_INSTALL/nested"; args=(--new "$TEST_INSTALL/nested") ;;
    symlink) mv "$TEST_INSTALL/config/application.yml" "$base/linked.yml"; ln -s "$base/linked.yml" "$TEST_INSTALL/config/application.yml" ;;
    mysql-change) sed -i 's/--max-connections=200/--max-connections=300/' "$fresh/docker-compose.yml" ;;
    mount-change) sed -i 's@./config/application.yml:@./config/other.yml:@' "$fresh/docker-compose.yml" ;;
    locked) mkdir "$TEST_INSTALL/.update.lock" ;;
    check-only) args=(--check-only) ;;
    check-only-missing) args=(--check-only) ;;
    optional-env) sed -i '/^CR_SUBNET=/d; /^APP_MEM_LIMIT=/d' "$TEST_INSTALL/.env" ;;
    export-success|export-failure) args=(--export-old-image) ;;
  esac
  bash "$fresh/scripts/update.sh" --new "$fresh" --install "$TEST_INSTALL" --tag test "${args[@]}" > "$base/output.log" 2>&1 || result=$?
  cmp "$TEST_INSTALL/config/application.yml" "$base/expected.yml"
  [ "$(cat "$TEST_INSTALL/data/mysql/sentinel")" = preserved ]
  [ "$(cat "$TEST_INSTALL/data/git-mirrors/sentinel")" = git-cache ]
  case "$TEST_CASE" in
    success|export-success|optional-env)
      [ "$result" = 0 ] || { cat "$base/output.log"; return 1; }
      [ "$(cat "$TEST_STATE")" = deployed ]
      local -a snapshots=("$TEST_INSTALL"/upgrade-snapshots/*)
      [ "${#snapshots[@]}" -eq 1 ]
      gzip -t "${snapshots[0]}"/database/*.sql.gz
      [ -f "${snapshots[0]}/scripts/original.sh" ]
      [ -f "$TEST_INSTALL/docs/fixture.md" ]
      [ -f "${snapshots[0]}/old-image-tag.txt" ]
      ! grep -q 'load -i images/codereview-app-old' "$TEST_LOG"
      if [ "$TEST_CASE" = export-success ]; then gzip -t "${snapshots[0]}/old-app-image.tar.gz"; fi ;;
    check-only)
      [ "$result" = 0 ] || { cat "$base/output.log"; return 1; }
      ! grep -Eq '^(load|tag|stop)|stop app|mysqldump|up -d' "$TEST_LOG"
      [ ! -e "$TEST_INSTALL/upgrade-snapshots" ]
      [ ! -e "$TEST_INSTALL/.update.lock" ] ;;
    race|backup-failure|export-failure|health-failure|api-failure|wrong-image|migration-failure|count-change)
      [ "$result" -ne 0 ]
      [ "$(cat "$TEST_STATE")" = stopped ]
      ! grep -q '^tag codereview-app:previous codereview-app:latest' "$TEST_LOG"
      if [[ "$TEST_CASE" = *backup-failure || "$TEST_CASE" = export-failure || "$TEST_CASE" = race ]]; then
        ! grep -q 'up -d app' "$TEST_LOG"
        [ ! -f "$TEST_INSTALL/scripts/deploy.sh" ]
      fi ;;
    *)
      [ "$result" -ne 0 ] || { cat "$base/output.log"; return 1; }
      [ "$(cat "$TEST_STATE")" = running ]
      ! grep -Eq 'stop app|mysqldump|up -d' "$TEST_LOG"
      cmp "$TEST_INSTALL/docker-compose.yml" "$base/expected-compose.yml" ;;
  esac
  if [ "$TEST_CASE" = check-only-missing ]; then
    ! grep -q '^load' "$TEST_LOG"
    [ ! -e "$TEST_INSTALL/upgrade-snapshots" ]
  fi
  [ "$TEST_CASE" = locked ] || [ ! -e "$TEST_INSTALL/.update.lock" ]
  passed=$((passed+1))
  printf 'PASS automatic update: %s\n' "$TEST_CASE"
}
for test_case in same-path nested-path symlink locked checksum mysql-change mount-change wrong-mount \
  wrong-schema active query-failure preflight-failure missing-tag config-failure compose-failure check-only \
  check-only-missing optional-env race backup-failure export-failure health-failure api-failure wrong-image migration-failure count-change success export-success; do
  run_case "$test_case"
done
printf 'PASS: %s automatic update scenarios\n' "$passed"

# 重放 Release 的部署附件复制及自测脚本排除，验证入口随包交付。
package="$TEST_ROOT/package"
mkdir -p "$package"
cp -r "$SOURCE/deploy/." "$package/"
mkdir -p "$package/docs"
cp "$SOURCE/docs/work/agent-redesign/record-deletion/operations.md" "$package/docs/record-deletion.md"
rm -f "$package/scripts/"_*selftest.sh
test -s "$package/scripts/update.sh"
test -s "$package/docs/record-deletion.md"
for script in "$package"/scripts/*.sh; do bash -n "$script"; done
printf '%s\n' 'PASS deployment attachment assembly and packaged script syntax (no images built)'
