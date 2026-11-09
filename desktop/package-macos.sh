#!/bin/sh
set -eu
cd "$(dirname "$0")"
SIGNING_IDENTITY=${DIYALAMP_MAC_SIGNING_IDENTITY:-}
SIGNING_KEYCHAIN=${DIYALAMP_MAC_SIGNING_KEYCHAIN:-}
NOTARY_PROFILE=${DIYALAMP_NOTARY_PROFILE:-}
if [ -n "$SIGNING_IDENTITY" ] || [ -n "$NOTARY_PROFILE" ] || [ -n "$SIGNING_KEYCHAIN" ]; then
  case "$SIGNING_IDENTITY" in
    "Developer ID Application: "?*) ;;
    *) echo 'A Developer ID Application signing identity is required.' >&2; exit 1 ;;
  esac
  : "${NOTARY_PROFILE:?Set DIYALAMP_NOTARY_PROFILE to a notarytool keychain profile.}"
fi

notarytool() {
  if [ -n "$SIGNING_KEYCHAIN" ]; then set -- "$@" --keychain "$SIGNING_KEYCHAIN"; fi
  xcrun notarytool "$@" --keychain-profile "$NOTARY_PROFILE"
}

notarize() {
  result="$DMG_WORK/notary-result.json"
  if notarytool submit "$1" --wait --timeout 15m --output-format json > "$result" &&
    [ "$(plutil -extract status raw -o - "$result")" = Accepted ]; then
    return
  fi
  if submission=$(plutil -extract id raw -o - "$result" 2>/dev/null); then
    notarytool log "$submission" >&2 || true
  fi
  echo "Apple did not accept the notarization submission for $(basename "$1")." >&2
  exit 1
}

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
manifest_paths=''
for dependency in build/input/deps/*.jar build/input/fx-deps/common/*.jar; do
  manifest_paths="$manifest_paths ${dependency#build/input/}"
done
printf 'Class-Path:%s\n' "$manifest_paths" > build/package-manifest.mf
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
set --
if [ -n "$SIGNING_IDENTITY" ]; then
  set -- --mac-sign --mac-signing-key-user-name "${SIGNING_IDENTITY#Developer ID Application: }"
  if [ -n "$SIGNING_KEYCHAIN" ]; then set -- "$@" --mac-signing-keychain "$SIGNING_KEYCHAIN"; fi
fi
"$JDK/bin/jpackage" --type app-image --name diyalamp --input build/input --main-jar diyalamp.jar --main-class com.diyalamp.desktop.DesktopLauncher --runtime-image "$RUNTIME" --app-version "$VERSION" --vendor diyalamp --description 'Local Markdown editor for macOS. No account required.' --mac-package-identifier com.diyalamp.desktop --icon build/diyalamp.icns --java-options "--module-path=\$APPDIR/fx-deps/$FX_PLATFORM" --java-options '--add-modules=javafx.controls' --java-options '--enable-native-access=javafx.graphics,ALL-UNNAMED' --java-options '-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua' --dest "$APP_DEST" "$@"
APP="$APP_DEST/diyalamp.app"
DMG_WORK=$(mktemp -d "$PWD/build/dmg-work.XXXXXX")
trap 'rm -rf "$DMG_WORK"' EXIT
if [ -n "$SIGNING_IDENTITY" ]; then
  codesign --verify --deep --strict "$APP"
  codesign --display --verbose=4 "$APP" 2>&1 | grep -F -x "Authority=$SIGNING_IDENTITY"
  # The in-app updater stages the application without Finder, so give it its own ticket.
  ditto -c -k --keepParent "$APP" "$DMG_WORK/diyalamp.zip"
  notarize "$DMG_WORK/diyalamp.zip"
  xcrun stapler staple "$APP"
  xcrun stapler validate "$APP"
fi
TARGET="build/release/diyalamp-${VERSION}-macos-${ARCH}.dmg"
"$JDK/bin/jpackage" --type dmg --name diyalamp --app-image "$APP" --app-version "$VERSION" --vendor diyalamp --dest "$DMG_WORK"
DMG="$DMG_WORK/diyalamp-${VERSION}.dmg"
# Compare lossless formats on the same app image and keep the smallest.
for format in UDZO ULMO; do
  candidate="$DMG_WORK/diyalamp-${VERSION}-$format.dmg"
  set --
  if [ "$format" = UDZO ]; then set -- -imagekey zlib-level=9; fi
  if hdiutil convert "$DMG" -format "$format" "$@" -ov -o "$candidate" >/dev/null &&
    [ "$(wc -c < "$candidate")" -lt "$(wc -c < "$DMG")" ]; then
    mv "$candidate" "$DMG"
  else
    rm -f "$candidate"
  fi
done
if [ -n "$SIGNING_IDENTITY" ]; then
  set --
  if [ -n "$SIGNING_KEYCHAIN" ]; then set -- --keychain "$SIGNING_KEYCHAIN"; fi
  codesign --force --sign "$SIGNING_IDENTITY" --timestamp "$@" "$DMG"
  notarize "$DMG"
  xcrun stapler staple "$DMG"
  xcrun stapler validate "$DMG"
fi
if [ -f "$TARGET" ]; then mv "$TARGET" "$TARGET.previous-$(date +%s)"; fi
mv "$DMG" "$TARGET"
(cd build/release && shasum -a 256 "$(basename "$TARGET")") > "$TARGET.sha256"
printf 'Ready: %s\n' "$TARGET"
