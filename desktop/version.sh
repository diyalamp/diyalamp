#!/bin/sh
set -eu
cd "$(dirname "$0")"
version=$(sed -n 's/^  public static final String VERSION = "\([0-9][0-9.]*\)";$/\1/p' src/main/java/com/diyalamp/desktop/Diyalamp.java)
printf '%s\n' "$version" | grep -Eq '^[1-9][0-9]*\.[0-9]+\.[0-9]+$' || { echo 'Expected a release version in Diyalamp.VERSION' >&2; exit 1; }
printf '%s\n' "$version"
