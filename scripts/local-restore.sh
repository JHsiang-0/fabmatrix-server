#!/usr/bin/env sh
set -eu

backup_dir="${1:?用法: local-restore.sh <备份目录> [数据目录] RESTORE}"
data_dir="${2:-./data}"
confirmation="${3:-}"

if [ "$confirmation" != "RESTORE" ]; then
  echo "为避免误覆盖，请使用第三个参数 RESTORE 确认恢复。" >&2
  exit 2
fi
if [ ! -f "$backup_dir/farm.db" ]; then
  echo "备份目录中缺少 farm.db: $backup_dir" >&2
  exit 1
fi

stamp="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$data_dir"
preserved="$data_dir/pre-restore-$stamp"
mkdir -p "$preserved"
if [ -f "$data_dir/farm.db" ]; then cp "$data_dir/farm.db" "$preserved/farm.db"; fi
if [ -d "$data_dir/files" ]; then mv "$data_dir/files" "$preserved/files"; fi
cp "$backup_dir/farm.db" "$data_dir/farm.db"
if [ -d "$backup_dir/files" ]; then cp -R "$backup_dir/files" "$data_dir/files"; fi

echo "Local Edition restore completed: $data_dir"
echo "Previous data preserved at: $preserved"
