#!/bin/sh
set -eu
cd "$(dirname "$0")"
sh test.sh
JDK="${DIYALAMP_JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || dirname "$(dirname "$(command -v javac)")")}"
VERSION=1.0.0
ARCH=$(uname -m)
mkdir -p build/input/deps build/release build/icon.iconset
cp build/diyalamp.jar build/input/
cp build/deps/flatlaf-3.7.2.jar build/input/deps/
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" src/main/resources/lamp.png --out "build/icon.iconset/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -z "$double" "$double" src/main/resources/lamp.png --out "build/icon.iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns build/icon.iconset -o build/diyalamp.icns
# Outputs live only under ignored build/. Existing release artifacts are not overwritten.
if [ ! -d build/runtime ]; then
  "$JDK/bin/jlink" --add-modules java.base,java.desktop,java.prefs,java.logging,java.xml,jdk.unsupported --strip-debug --no-man-pages --no-header-files --compress=2 --output build/runtime
fi
if [ -d build/app/diyalamp.app ]; then mv build/app/diyalamp.app "build/app/diyalamp-previous-$(date +%s).app"; fi
"$JDK/bin/jpackage" --type app-image --name diyalamp --input build/input --main-jar diyalamp.jar --main-class com.diyalamp.desktop.Diyalamp --runtime-image build/runtime --app-version "$VERSION" --vendor diyalamp --description 'A quiet place for your thoughts. Local Markdown writing for macOS.' --mac-package-identifier com.diyalamp.desktop --icon build/diyalamp.icns --java-options '-Dapple.laf.useScreenMenuBar=true' --dest build/app
TARGET="build/release/diyalamp-${VERSION}-macos-${ARCH}.dmg"
if [ -f "$TARGET" ]; then mv "$TARGET" "$TARGET.previous-$(date +%s)"; fi
"$JDK/bin/jpackage" --type dmg --name diyalamp --app-image build/app/diyalamp.app --app-version "$VERSION" --vendor diyalamp --dest build/dmg
mv "build/dmg/diyalamp-${VERSION}.dmg" "$TARGET"
(cd build/release && shasum -a 256 "$(basename "$TARGET")") > "$TARGET.sha256"
printf 'Ready: %s\n' "$TARGET"
