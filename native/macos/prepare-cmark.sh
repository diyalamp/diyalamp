#!/bin/sh
set -eu
export LC_ALL=C

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  printf 'Usage: sh prepare-cmark.sh BUILD_DIR [LOCAL_ARCHIVE]\n' >&2
  exit 2
fi

version=0.29.0.gfm.13
source_name="cmark-gfm-$version"
archive_name="$source_name.tar.gz"
archive_sha256=5abc61798ebd9de5660bc076443c07abad2b8d15dbc11094a3a79644b8ad243a
archive_url="https://codeload.github.com/github/cmark-gfm/tar.gz/refs/tags/$version"

case "$1" in
  /*) output="$1" ;;
  *) output="$PWD/$1" ;;
esac
while [ "${output%/}" != "$output" ]; do output="${output%/}"; done
if [ -z "$output" ] || [ -L "$output" ] || { [ -e "$output" ] && [ ! -d "$output" ]; }; then
  printf 'Expected a new or empty build directory.\n' >&2
  exit 2
fi
if [ -d "$output" ] && [ -n "$(find "$output" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  printf 'Build directory must be empty: %s\n' "$output" >&2
  exit 2
fi

parent=$(dirname "$output")
mkdir -p "$parent"
temporary=$(mktemp -d "$parent/.cmark-build.XXXXXX")
trap 'rm -rf "$temporary"' EXIT
trap 'exit 1' HUP INT TERM

archive="$temporary/$archive_name"
if [ "$#" -eq 2 ]; then
  case "$2" in
    /*) cp "$2" "$archive" ;;
    *) cp "$PWD/$2" "$archive" ;;
  esac
else
  /usr/bin/curl --proto '=https' --proto-redir '=https' --fail --location \
    --silent --show-error --retry 2 --connect-timeout 15 --max-time 90 \
    --output "$archive" "$archive_url"
fi

checksum=$(shasum -a 256 "$archive")
if [ "${checksum%% *}" != "$archive_sha256" ]; then
  printf 'SHA-256 mismatch for cmark-gfm %s.\n' "$version" >&2
  exit 1
fi

tar -xzf "$archive" -C "$temporary"
upstream="$temporary/$source_name"
headers="$temporary/include-full"
mkdir "$headers"
cp "$upstream/src/cmark-gfm.h" "$upstream/src/cmark-gfm-extension_api.h" \
  "$upstream/extensions/cmark-gfm-core-extensions.h" "$headers/"

# Apple Clang provides the three features tested by upstream's CMake build.
sed 's/^#cmakedefine \([_A-Z]*\)$/#define \1 1/' \
  "$upstream/src/config.h.in" > "$headers/config.h"
sed -e 's/@PROJECT_VERSION_MAJOR@/0/g' -e 's/@PROJECT_VERSION_MINOR@/29/g' \
  -e 's/@PROJECT_VERSION_PATCH@/0/g' -e 's/@PROJECT_VERSION_GFM@/13/g' \
  "$upstream/src/cmark-gfm_version.h.in" > "$headers/cmark-gfm_version.h"

cat > "$headers/cmark-gfm_export.h" <<'EOF'
#ifndef CMARK_GFM_EXPORT_H
#define CMARK_GFM_EXPORT_H
#define CMARK_GFM_EXPORT
#define CMARK_GFM_NO_EXPORT
#define CMARK_GFM_DEPRECATED __attribute__((deprecated))
#define CMARK_GFM_DEPRECATED_EXPORT CMARK_GFM_EXPORT CMARK_GFM_DEPRECATED
#define CMARK_GFM_DEPRECATED_NO_EXPORT CMARK_GFM_NO_EXPORT CMARK_GFM_DEPRECATED
#endif
EOF

cat > "$headers/module.modulemap" <<'EOF'
module CMarkGFM [system] {
    header "cmark-gfm.h"
    header "cmark-gfm-extension_api.h"
    header "cmark-gfm-core-extensions.h"
    export *
}
EOF

build_library() {
  source_dir="$1"
  library="$2"
  shift 2
  objects="$temporary/objects-$architecture-${library##*/}"
  mkdir "$objects"
  for source_file do
    /usr/bin/xcrun clang -target "$architecture-apple-macos15.0" -Oz -DNDEBUG \
      -DCMARK_GFM_STATIC_DEFINE -DCMARK_GFM_EXTENSIONS_STATIC_DEFINE \
      -std=c99 -fvisibility=hidden -I "$headers" \
      -I "$upstream/src" -I "$upstream/extensions" \
      -c "$source_dir/$source_file.c" -o "$objects/$source_file.o"
  done
  ZERO_AR_DATE=1 /usr/bin/xcrun ar rcs "$library" "$objects"/*.o
}

for architecture in arm64 x86_64; do
  mkdir "$temporary/$architecture"
  build_library "$upstream/src" "$temporary/$architecture/libcmark-gfm.a" \
    cmark node iterator blocks inlines scanners utf8 buffer references footnotes \
    map render man xml html commonmark plaintext latex houdini_href_e houdini_html_e \
    houdini_html_u cmark_ctype arena linked_list syntax_extension registry plugin
  build_library "$upstream/extensions" "$temporary/$architecture/libcmark-gfm-extensions.a" \
    core-extensions table strikethrough autolink tagfilter ext_scanners tasklist
done

if [ -d "$output" ]; then rmdir "$output"; fi
mkdir "$output"
mkdir "$output/$source_name"
cp "$upstream/COPYING" "$output/$source_name/"
mv "$headers" "$temporary/arm64" "$temporary/x86_64" "$output/"
printf 'Prepared cmark-gfm %s in %s\n' "$version" "$output"
