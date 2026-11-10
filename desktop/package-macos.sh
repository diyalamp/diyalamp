#!/bin/sh
set -eu
cd "$(dirname "$0")"
sh test.sh
. ./java-home.sh
VERSION=$(sh version.sh)
ARCH=$(uname -m)
rm -rf build/input
mkdir -p build/input/deps build/input/fx-deps/common "build/input/fx-deps/$FX_PLATFORM" build/release build/icon.iconset
# The Mac launcher uses JavaFX. Keep the legacy Swing libraries in the
# source build and test classpath, outside the shipped app.
cp build/deps/commonmark-0.32.0.jar build/deps/commonmark-ext-gfm-strikethrough-0.32.0.jar build/input/deps/
cp build/fx-deps/common/*.jar build/input/fx-deps/common/
cp "build/fx-deps/$FX_PLATFORM/"*.jar "build/input/fx-deps/$FX_PLATFORM/"
{
  printf 'Class-Path: \n'
  for dependency in build/input/deps/*.jar build/input/fx-deps/common/*.jar; do
    printf ' %s \n' "${dependency#build/input/}"
  done
} > build/package-manifest.mf
"$JDK/bin/jar" --create --file build/input/diyalamp.jar --main-class com.diyalamp.desktop.DesktopLauncher --manifest build/package-manifest.mf -C build/classes .
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" src/main/resources/app-icon.png --out "build/icon.iconset/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -z "$double" "$double" src/main/resources/app-icon.png --out "build/icon.iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns build/icon.iconset -o build/diyalamp.icns
# Outputs live only under ignored build/. Existing release artifacts are not overwritten.
RUNTIME="build/runtime-jdk${JAVA_MAJOR}-fx27-tls"
if [ ! -d "$RUNTIME" ]; then
  "$JDK/bin/jlink" --add-modules java.base,java.desktop,java.prefs,java.logging,java.xml,jdk.unsupported,jdk.crypto.ec --strip-debug --no-man-pages --no-header-files --compress=zip-9 --output "$RUNTIME"
fi
APP_DEST="build/app-$VERSION"
if [ -d "$APP_DEST/diyalamp.app" ]; then mv "$APP_DEST/diyalamp.app" "$APP_DEST/diyalamp-previous-$(date +%s).app"; fi
"$JDK/bin/jpackage" --type app-image --name diyalamp --input build/input --main-jar diyalamp.jar --main-class com.diyalamp.desktop.DesktopLauncher --runtime-image "$RUNTIME" --app-version "$VERSION" --vendor diyalamp --description 'Local Markdown editor for macOS. No account required.' --mac-package-identifier com.diyalamp.desktop --icon build/diyalamp.icns --java-options "--module-path=\$APPDIR/fx-deps/$FX_PLATFORM" --java-options '--add-modules=javafx.controls' --java-options '--enable-native-access=javafx.graphics,ALL-UNNAMED' --java-options '-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua' --dest "$APP_DEST"
TARGET="build/release/diyalamp-${VERSION}-macos-${ARCH}.dmg"
"$JDK/bin/jpackage" --type dmg --name diyalamp --app-image "$APP_DEST/diyalamp.app" --app-version "$VERSION" --vendor diyalamp --dest build/dmg
DMG="build/dmg/diyalamp-${VERSION}.dmg"
# Compare lossless formats on the same app image and keep the smallest.
for format in UDZO ULMO; do
  candidate="build/dmg/diyalamp-${VERSION}-$format.dmg"
  set --
  if [ "$format" = UDZO ]; then set -- -imagekey zlib-level=9; fi
  if hdiutil convert "$DMG" -format "$format" "$@" -ov -o "$candidate" >/dev/null &&
    [ "$(wc -c < "$candidate")" -lt "$(wc -c < "$DMG")" ]; then
    mv "$candidate" "$DMG"
  else
    rm -f "$candidate"
  fi
done
if [ -f "$TARGET" ]; then mv "$TARGET" "$TARGET.previous-$(date +%s)"; fi
mv "$DMG" "$TARGET"
(cd build/release && shasum -a 256 "$(basename "$TARGET")") > "$TARGET.sha256"
printf 'Ready: %s\n' "$TARGET"
