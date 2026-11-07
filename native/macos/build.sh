#!/bin/sh
set -eu
cd "$(dirname "$0")"
sources=${1:?Path to a DiyaLamp desktop src/main/resources folder}
upstream=${2:?Path to the pinned cmark-gfm build folder}
mkdir -p build
output=$(mktemp -d "$PWD/build/workspace.XXXXXX")
app="$output/staging/DiyaLamp Native Preview.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources/Fonts"
for architecture in arm64 x86_64; do
  xcrun swiftc -parse-as-library -Osize -whole-module-optimization \
    -target "$architecture-apple-macos15.0" -I "$upstream/include-full" \
    main.swift CmarkPatch.swift RenderedStyle.swift FolderWorkspace.swift \
    "$upstream/$architecture/libcmark-gfm-extensions.a" "$upstream/$architecture/libcmark-gfm.a" \
    -o "$output/probe-$architecture"
done
binary="$app/Contents/MacOS/DiyaLampNativeProbe"
lipo -create "$output/probe-arm64" "$output/probe-x86_64" -output "$binary"
strip -x "$binary"
iconset="$output/diyalamp.iconset"
mkdir "$iconset"
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$sources/app-icon.png" --out "$iconset/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -z "$double" "$double" "$sources/app-icon.png" --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$iconset" -o "$app/Contents/Resources/diyalamp.icns"
cp "$upstream/cmark-gfm-0.29.0.gfm.13/COPYING" "$app/Contents/Resources/cmark-gfm-LICENSE.txt"
cp "$sources/lamp.png" "$app/Contents/Resources/"
cp "$sources"/fonts/Inter-*.ttf "$sources/fonts/Inter-LICENSE.txt" "$sources/fonts/PROVENANCE.txt" \
  "$app/Contents/Resources/Fonts/"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleDevelopmentRegion</key><string>en</string>
<key>CFBundleExecutable</key><string>DiyaLampNativeProbe</string>
<key>CFBundleIdentifier</key><string>com.diyalamp.preview.nativeworkspace</string>
<key>CFBundleName</key><string>DiyaLamp Native Preview</string>
<key>CFBundleIconFile</key><string>diyalamp.icns</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.0.1</string>
<key>CFBundleVersion</key><string>1</string>
<key>LSMinimumSystemVersion</key><string>15.0</string>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
codesign --force --sign - "$app"
codesign --verify --strict "$app"
app_bytes=$(find "$app" -type f -exec stat -f %z {} + | awk '{ total += $1 } END { print total }')
test "$app_bytes" -lt 5000000 || {
  printf 'App bundle is %s bytes and exceeds the 5 MB size budget.\n' "$app_bytes" >&2
  exit 1
}
dmg="$output/DiyaLamp-Native-Preview.dmg"
hdiutil create -format UDZO -imagekey zlib-level=9 -fs HFS+ -srcfolder "$output/staging" \
  -volname "DiyaLamp Native Preview" "$dmg"
dmg_bytes=$(stat -f %z "$dmg")
test "$dmg_bytes" -lt 5000000 || {
  printf 'Installer is %s bytes and exceeds the 5 MB size budget.\n' "$dmg_bytes" >&2
  exit 1
}
printf 'Universal DMG: %s bytes. App bundle: %s bytes, %s KiB on disk.\n' \
  "$dmg_bytes" "$app_bytes" "$(du -sk "$app" | cut -f1)"
(cd "$output" && shasum -a 256 "$(basename "$dmg")") > "$dmg.sha256"
printf 'Build directory: %s\n' "$output"
