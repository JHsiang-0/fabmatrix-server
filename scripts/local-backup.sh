#!/usr/bin/env sh
set -eu
data_root="${1:-./data}"
backup_root="${2:-$data_root/backups}"
database="$data_root/database/farm.db"
config="$data_root/config/application.yml"
files="$data_root/files"
[ -f "$database" ] || { echo "统一数据根缺少必需文件: $database" >&2; exit 1; }
[ -f "$config" ] || { echo "统一数据根缺少必需文件: $config" >&2; exit 1; }
stamp=$(date +%Y%m%d-%H%M%S)
target="$backup_root/farm-local-$stamp"
mkdir -p "$target/database" "$target/config" "$target/files"
cp "$database" "$target/database/farm.db"
awk '
  BEGIN { sq = sprintf("%c", 39) }
  /^[[:space:]]*url:[[:space:]].*jdbc:sqlite:/ {
    match($0, /^[[:space:]]*url:[[:space:]]*/)
    print substr($0, 1, RLENGTH) sq "jdbc:sqlite:${FARM_DATA_DIR}/database/farm.db" sq
    next
  }
  /^[[:space:]]*storage-path:[[:space:]]*/ {
    match($0, /^[[:space:]]*storage-path:[[:space:]]*/)
    print substr($0, 1, RLENGTH) sq "${FARM_DATA_DIR}/files" sq
    next
  }
  /^[[:space:]]*name:[[:space:]].*[\\/]logs[\\/]farm\.log/ {
    match($0, /^[[:space:]]*name:[[:space:]]*/)
    print substr($0, 1, RLENGTH) sq "${FARM_DATA_DIR}/logs/farm.log" sq
    next
  }
  { print }
' "$config" > "$target/config/application.yml"
if [ -d "$files" ]; then cp -R "$files"/. "$target/files"/; fi
echo "Local Edition backup created: $target"
echo "Consistency: stop FabMatrixServer before running this script; no service control is performed."
