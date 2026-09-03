#!/usr/bin/env sh
set -eu
data_dir="${1:-./data}"
backup_dir="${2:-./backups}"
stamp="$(date +%Y%m%d-%H%M%S)"
target="$backup_dir/farm-local-$stamp"
mkdir -p "$target"
cp "$data_dir/farm.db" "$target/farm.db"
if [ -d "$data_dir/files" ]; then cp -R "$data_dir/files" "$target/files"; fi
echo "Local Edition backup created: $target"
