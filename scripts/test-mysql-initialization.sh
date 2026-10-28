#!/bin/sh
# Real MySQL initialization smoke test. Uses only an already installed image.
set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
schema_path="$project_root/src/main/resources/db/init/mysql-schema.sql"
audit_image=mysql:8.4
audit_container_id=''

docker image inspect "$audit_image" >/dev/null

cleanup() {
    if [ -n "$audit_container_id" ]; then
        docker rm -f "$audit_container_id" >/dev/null
    fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

# No persistent volume, published port or network. Never target farm-mysql.
audit_container_id=$(docker run -d --network none --tmpfs /var/lib/mysql \
    --mount "type=bind,source=$schema_path,target=/docker-entrypoint-initdb.d/01-mysql-schema.sql,readonly" \
    -e MYSQL_ALLOW_EMPTY_PASSWORD=yes -e MYSQL_DATABASE=farm \
    "$audit_image" --default-time-zone=+08:00 \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci)

ready=false
for attempt in $(seq 1 60); do
    if docker logs "$audit_container_id" 2>&1 | grep -q 'MySQL init process done' \
        && docker exec "$audit_container_id" mysqladmin ping --silent >/dev/null 2>&1; then
        ready=true
        break
    fi
    if [ "$(docker inspect --format '{{.State.Running}}' "$audit_container_id")" != true ]; then
        break
    fi
    sleep 1
done
if [ "$ready" != true ]; then
    docker logs "$audit_container_id" >&2
    echo 'FAIL: isolated MySQL did not finish initialization' >&2
    exit 1
fi

query() {
    docker exec "$audit_container_id" mysql -u root --default-character-set=utf8mb4 \
        --batch --skip-column-names farm -e "$1"
}

expect_equal() {
    actual=$(query "$2")
    if [ "$actual" != "$1" ]; then
        echo "FAIL: $3 (expected $1, got $actual)" >&2
        exit 1
    fi
    echo "PASS: $3"
}

expect_rejected() {
    if failure=$(query "START TRANSACTION; $1; ROLLBACK;" 2>&1); then
        echo "FAIL: $2 was accepted" >&2
        exit 1
    fi
    case "$failure" in
        *"ERROR $3 ("*) ;;
        *) echo "FAIL: $2 failed for an unexpected reason: $failure" >&2; exit 1 ;;
    esac
    echo "PASS: $2 rejected"
}

expect_equal 12 "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='farm'" 'all current tables'
for table in farm_user farm_printer farm_print_file farm_print_job farm_printer_status_history \
    farm_printer_execution farm_dispatch_plan farm_dispatch_plan_item farm_audit_log; do
    expect_equal 0 "SELECT COUNT(*) FROM $table" "$table starts empty"
done
expect_equal 1 "SELECT COUNT(*) FROM farm_setup_state WHERE id=1 AND initialized=0" 'first administrator setup latch'
expect_equal 1 "SELECT COUNT(*) FROM farm_dashboard_layout WHERE id=1 AND row_count=4 AND column_count=12 AND version=1 AND JSON_EXTRACT(aisles_json,'\$[0].afterIndex')=7 AND JSON_UNQUOTE(JSON_EXTRACT(aisles_json,'\$[0].label'))='过道'" 'default layout and Chinese text'
expect_equal 3 "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='farm' AND table_name='farm_printer_status_history' AND column_name IN ('task_source','farm_job_id','task_match_reason')" 'task observation fields'
expect_equal 1 "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='farm' AND table_name='farm_user' AND column_name='token_version'" 'session invalidation field'
expect_equal QUEUED "SELECT column_default FROM information_schema.columns WHERE table_schema='farm' AND table_name='farm_print_job' AND column_name='status'" 'queued task default'

expect_rejected "INSERT INTO farm_printer(name,grid_row,grid_col) VALUES ('probe',1,NULL)" 'partial printer coordinate' 3819
expect_rejected "INSERT INTO farm_printer(name,grid_row,grid_col) VALUES ('probe',0,1)" 'nonpositive printer coordinate' 3819
expect_rejected "INSERT INTO farm_printer(name,grid_row,grid_col) VALUES ('probe-a',1,1),('probe-b',1,1)" 'duplicate printer slot' 1062
expect_rejected "INSERT INTO farm_dashboard_layout(id,row_count,column_count,aisles_json,version) VALUES (2,4,12,'[]',1)" 'second dashboard layout' 3819
expect_rejected "UPDATE farm_dashboard_layout SET row_count=31 WHERE id=1" 'oversized dashboard layout' 3819
expect_rejected "INSERT INTO farm_print_job(user_id,idempotency_key) VALUES (1,'probe'),(1,'probe')" 'duplicate task idempotency key' 1062
expect_rejected "INSERT INTO farm_print_job(user_id,retry_of_job_id) VALUES (1,100),(1,100)" 'duplicate retry child' 1062
expect_rejected "INSERT INTO farm_print_job(user_id,root_job_id,attempt_number) VALUES (1,100,2),(1,100,2)" 'duplicate task attempt' 1062
expect_rejected "INSERT INTO farm_print_file(original_name,safe_name,user_id,is_folder,is_default_folder) VALUES ('a','a',1,1,1),('b','b',1,1,1)" 'duplicate default folder' 1062
expect_rejected "INSERT INTO farm_user(username,password_hash) VALUES ('probe','test-only'),('probe','test-only')" 'duplicate username' 1062

# A rerun must fail rather than silently treating an existing schema as upgraded.
query "INSERT INTO farm_user(username,password_hash) VALUES ('preserved','test-only')" >/dev/null
if docker exec -i "$audit_container_id" mysql -u root --default-character-set=utf8mb4 farm \
    < "$schema_path" >/dev/null 2>&1; then
    echo 'FAIL: initialization unexpectedly succeeded on an existing schema' >&2
    exit 1
fi
expect_equal 1 "SELECT COUNT(*) FROM farm_dashboard_layout" 'rerun preserves existing system state'
expect_equal 1 "SELECT COUNT(*) FROM farm_user WHERE username='preserved'" 'rerun preserves existing business data'

# Upgrade scripts must also tolerate the current schema. Existing users close
# setup permanently; later user-row cleanup must never reopen this latch.
for migration in 18-v2-print-job-query-semantics-mysql.sql \
    21-printer-task-observation-mysql.sql 22-printer-execution-mysql.sql \
    23-align-setup-state-and-job-default.sql; do
    docker exec -i "$audit_container_id" mysql -u root --default-character-set=utf8mb4 farm \
        < "$project_root/src/main/resources/db/migration/mysql/$migration" >/dev/null
done
expect_equal 1 "SELECT initialized FROM farm_setup_state WHERE id=1" 'upgrade closes setup for existing users'
query "DELETE FROM farm_user WHERE username='preserved'" >/dev/null
docker exec -i "$audit_container_id" mysql -u root --default-character-set=utf8mb4 farm \
    < "$project_root/src/main/resources/db/migration/mysql/23-align-setup-state-and-job-default.sql" >/dev/null
expect_equal 1 "SELECT initialized FROM farm_setup_state WHERE id=1" 'repeated upgrade never reopens setup'
expect_equal QUEUED "SELECT column_default FROM information_schema.columns WHERE table_schema='farm' AND table_name='farm_print_job' AND column_name='status'" 'upgrade preserves queued default'
# Emulate the previous account schema inside this disposable, unpublished container.
query "INSERT INTO farm_user(username,password_hash) VALUES ('account-upgrade-preserved','test-only')" >/dev/null
docker exec "$audit_container_id" mysql -uroot farm -e \
    'DROP TABLE farm_login_session; ALTER TABLE farm_user DROP COLUMN must_change_password;'
docker exec -i "$audit_container_id" mysql -uroot farm \
    < "$project_root/src/main/resources/db/migration/mysql/24-account-security-mysql.sql"
expect_equal 0 "SELECT must_change_password FROM farm_user WHERE username='account-upgrade-preserved'" 'existing users are not forced to change password'
expect_equal test-only "SELECT password_hash FROM farm_user WHERE username='account-upgrade-preserved'" 'upgrade preserves password hash'
expect_equal 0 "SELECT COUNT(*) FROM farm_login_session" 'upgrade creates empty session registry'

echo 'PASS: MySQL initialization and account-security upgrade smoke test'
