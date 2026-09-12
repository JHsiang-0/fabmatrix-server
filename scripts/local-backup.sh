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
cp "$config" "$target/config/application.yml"
if [ -d "$files" ]; then cp -R "$files"/. "$target/files"/; fi
echo "Local Edition backup created: $target"
echo "Consistency: stop FabMatrixServer before running this script; no service control is performed."
