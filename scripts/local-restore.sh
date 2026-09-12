#!/usr/bin/env sh
set -eu
backup_dir="${1:?用法: local-restore.sh <备份目录> [数据根] RESTORE}"
data_root="${2:-./data}"
confirm="${3:-}"
[ "$confirm" = RESTORE ] || { echo "恢复必须显式提供 RESTORE" >&2; exit 1; }
backup_db="$backup_dir/database/farm.db"
backup_config="$backup_dir/config/application.yml"
backup_files="$backup_dir/files"
[ -f "$backup_db" ] || { echo "备份目录中缺少 database/farm.db: $backup_dir" >&2; exit 1; }
[ -f "$backup_config" ] || { echo "备份目录中缺少 config/application.yml: $backup_dir" >&2; exit 1; }
[ -d "$backup_files" ] || { echo "备份目录中缺少 files: $backup_dir" >&2; exit 1; }
stamp=$(date +%Y%m%d-%H%M%S)
preserved="$data_root/backups/pre-restore-$stamp"
mkdir -p "$preserved"
for relative in database config files; do
  if [ -e "$data_root/$relative" ]; then cp -R "$data_root/$relative" "$preserved/$relative"; fi
done
mkdir -p "$data_root/database" "$data_root/config" "$data_root/files"
cp "$backup_db" "$data_root/database/farm.db"
cp "$backup_config" "$data_root/config/application.yml"
cp -R "$backup_files"/. "$data_root/files"/
echo "Local Edition restore completed: $data_root"
echo "Previous config/database/files preserved at: $preserved"
echo "Consistency: stop FabMatrixServer before running this script; no service control is performed."
