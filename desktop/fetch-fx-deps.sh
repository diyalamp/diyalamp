#!/bin/sh
set -eu
cd "$(dirname "$0")"
. ./java-home.sh
while read -r platform checksum coordinate; do
  case "$platform" in ''|'#'*) continue ;; esac
  if [ "$platform" != common ] && [ "$platform" != "$FX_PLATFORM" ]; then continue; fi
  directory="build/fx-deps/$platform"
  mkdir -p "$directory"
  target="$directory/${coordinate##*/}"
  if [ ! -f "$target" ]; then
    curl --fail --location --proto '=https' --tlsv1.2 "https://repo.maven.apache.org/maven2/$coordinate" -o "$target.tmp"
    mv "$target.tmp" "$target"
  fi
  printf '%s  %s\n' "$checksum" "$target" | shasum -a 256 -c -
done < fx-dependencies.sha256
