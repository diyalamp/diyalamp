#!/bin/sh
set -eu
cd "$(dirname "$0")"
upstream=${1:?Path to the pinned cmark-gfm build folder}
architecture=$(uname -m)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

xcrun swiftc -parse-as-library -Osize -whole-module-optimization \
  -target "$architecture-apple-macos15.0" -I "$upstream/include-full" \
  WritingTests.swift CmarkPatch.swift \
  "$upstream/$architecture/libcmark-gfm-extensions.a" "$upstream/$architecture/libcmark-gfm.a" \
  -o "$scratch/writing"
"$scratch/writing"

xcrun swiftc -parse-as-library -target "$architecture-apple-macos15.0" \
  FolderWorkspace.swift FolderWorkspaceTests.swift -o "$scratch/folders"
"$scratch/folders"
