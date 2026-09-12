#!/usr/bin/env sh
set -eu

backup_dir="${1:?用法: local-restore.sh <备份目录> [数据根] RESTORE}"
data_root="${2:-./data}"
confirm="${3:-}"
[ "$confirm" = RESTORE ] || { echo "恢复必须显式提供 RESTORE" >&2; exit 1; }
case "$data_root" in ''|'/') echo "数据根不能是根目录" >&2; exit 1 ;; esac

backup_db="$backup_dir/database/farm.db"
backup_config="$backup_dir/config/application.yml"
backup_files="$backup_dir/files"
[ -f "$backup_db" ] || { echo "备份目录中缺少 database/farm.db: $backup_dir" >&2; exit 1; }
[ -f "$backup_config" ] || { echo "备份目录中缺少 config/application.yml: $backup_dir" >&2; exit 1; }
[ -d "$backup_files" ] || { echo "备份目录中缺少 files: $backup_dir" >&2; exit 1; }

normalize_config() {
  source_file="$1"
  target_file="$2"
  has_sqlite=0
  has_datasource=0
  has_spring=0
  grep -Eq '^[[:space:]]+url:[[:space:]].*jdbc:sqlite:' "$source_file" && has_sqlite=1
  grep -Eq '^  datasource:[[:space:]]*($|#)' "$source_file" && has_datasource=1
  grep -Eq '^spring:[[:space:]]*($|#)' "$source_file" && has_spring=1
  awk -v has_sqlite="$has_sqlite" -v has_datasource="$has_datasource" -v has_spring="$has_spring" '
    BEGIN { sq = sprintf("%c", 39) }
    BEGIN {
      if (!has_sqlite && !has_datasource && !has_spring) {
        print "spring:"
        print "  datasource:"
        print "    url: " sq "jdbc:sqlite:${FARM_DATA_DIR}/database/farm.db" sq
      }
    }
    /^spring:[[:space:]]*($|#)/ {
      print
      if (!has_sqlite && !has_datasource) {
        print "  datasource:"
        print "    url: " sq "jdbc:sqlite:${FARM_DATA_DIR}/database/farm.db" sq
      }
      next
    }
    /^  datasource:[[:space:]]*($|#)/ {
      print
      if (!has_sqlite) print "    url: " sq "jdbc:sqlite:${FARM_DATA_DIR}/database/farm.db" sq
      next
    }
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
    /^[[:space:]]*path:[[:space:]].*[\\/]logs/ {
      match($0, /^[[:space:]]*path:[[:space:]]*/)
      print substr($0, 1, RLENGTH) sq "${FARM_DATA_DIR}/logs" sq
      next
    }
    /^[[:space:]]*name:[[:space:]]*(["]?farm["]?|.*[\\/]logs[\\/]farm\.log)/ {
      match($0, /^[[:space:]]*name:[[:space:]]*/)
      print substr($0, 1, RLENGTH) sq "${FARM_DATA_DIR}/logs/farm.log" sq
      next
    }
    { print }
  ' "$source_file" > "$target_file"
}

stamp="$(date +%Y%m%d-%H%M%S)-$$"
preserved="$data_root/backups/pre-restore-$stamp"
staging="$data_root/.restore-staging-$stamp"
preserved_names=''
installed_names=''
mkdir -p "$staging/database" "$staging/config" "$staging/files"
cp "$backup_db" "$staging/database/farm.db"
normalize_config "$backup_config" "$staging/config/application.yml"
cp -R "$backup_files"/. "$staging/files"/
mkdir -p "$preserved"

rollback() {
  set +e
  for relative in $installed_names; do rm -rf -- "$data_root/$relative"; done
  for relative in $preserved_names; do
    if [ -e "$preserved/$relative" ] && [ ! -e "$data_root/$relative" ]; then
      mv "$preserved/$relative" "$data_root/$relative"
    fi
  done
  echo "恢复失败，已尽力回滚原数据；请检查 $preserved 和 $staging" >&2
}

perform_restore() {
  for relative in config database files; do
    if [ -e "$data_root/$relative" ]; then
      mv "$data_root/$relative" "$preserved/$relative" || return 1
      preserved_names="$relative $preserved_names"
    fi
  done
  for relative in config database files; do
    mv "$staging/$relative" "$data_root/$relative" || return 1
    installed_names="$relative $installed_names"
  done
}

if ! perform_restore; then
  rollback
  exit 1
fi
rmdir "$staging" 2>/dev/null || true
echo "Local Edition restore completed: $data_root"
echo "Previous config/database/files moved to: $preserved"
echo "Consistency: stop FabMatrixServer first; config/database/files were fully replaced, including stale SQLite WAL/SHM files."
echo "ACL note: Linux does not apply the Windows SYSTEM/Administrators policy; retain appropriate ownership and mode controls on this data root."
