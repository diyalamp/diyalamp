#!/bin/sh
set -eu
cd "$(dirname "$0")"
sh test.sh
. ./java-home.sh
VERSION=$(sh version.sh)
ARCH=$(uname -m)
# Optional Developer ID signing and notarization. Without an identity the
# installers stay ad hoc signed, as before.
#   DIYALAMP_SIGN_IDENTITY   "Developer ID Application: Name (TEAMID)"
#   DIYALAMP_SIGN_KEYCHAIN   keychain holding that identity (default: search list)
#   DIYALAMP_NOTARY_PROFILE  notarytool keychain profile, or all three of
#   DIYALAMP_NOTARY_KEY, DIYALAMP_NOTARY_KEY_ID, DIYALAMP_NOTARY_ISSUER
#                            (App Store Connect API key path, key ID, issuer ID)
SIGN_IDENTITY=${DIYALAMP_SIGN_IDENTITY:-}
SIGN_KEYCHAIN=${DIYALAMP_SIGN_KEYCHAIN:-}
codesign_id() {
  if [ -n "$SIGN_KEYCHAIN" ]; then
    codesign --force --timestamp --options runtime --keychain "$SIGN_KEYCHAIN" --sign "$SIGN_IDENTITY" "$@"
  else
    codesign --force --timestamp --options runtime --sign "$SIGN_IDENTITY" "$@"
  fi
}
if [ -n "$SIGN_IDENTITY" ]; then
  if [ -n "${DIYALAMP_NOTARY_PROFILE:-}" ]; then
    set -- --keychain-profile "$DIYALAMP_NOTARY_PROFILE"
  elif [ -n "${DIYALAMP_NOTARY_KEY:-}" ] && [ -n "${DIYALAMP_NOTARY_KEY_ID:-}" ] && [ -n "${DIYALAMP_NOTARY_ISSUER:-}" ]; then
    set -- --key "$DIYALAMP_NOTARY_KEY" --key-id "$DIYALAMP_NOTARY_KEY_ID" --issuer "$DIYALAMP_NOTARY_ISSUER"
  elif [ "${DIYALAMP_SKIP_NOTARIZATION:-}" = 1 ]; then
    set --
  else
    echo 'Set notarization credentials, or DIYALAMP_SKIP_NOTARIZATION=1 for a signed but unnotarized build.' >&2
    exit 1
  fi
  NOTARY_ARGS=$*
fi
rm -rf build/input
mkdir -p build/input/deps build/input/fx-deps/common "build/input/fx-deps/$FX_PLATFORM" build/release build/icon.iconset
# The Mac launcher uses JavaFX. Keep the legacy Swing libraries in the
# source build and test classpath, outside the shipped app.
cp build/deps/commonmark-0.32.0.jar build/deps/commonmark-ext-gfm-strikethrough-0.32.0.jar build/input/deps/
cp build/fx-deps/common/*.jar build/input/fx-deps/common/
cp "build/fx-deps/$FX_PLATFORM/"*.jar "build/input/fx-deps/$FX_PLATFORM/"
if [ -n "$SIGN_IDENTITY" ]; then
  # Notarization rejects unsigned Mach-O files, including dylibs inside jars.
  for archive in build/input/fx-deps/*/*.jar; do
    natives=$(unzip -Z1 "$archive" | grep -E '\.(dylib|jnilib)$' || true)
    [ -n "$natives" ] || continue
    work="build/native-sign/$(basename "$archive" .jar)"
    rm -rf "$work" && mkdir -p "$work"
    # shellcheck disable=SC2086 # entries are plain library names
    unzip -q "$archive" $natives -d "$work"
    (cd "$work" && for lib in $natives; do codesign_id "$lib"; done)
    "$JDK/bin/jar" --update --file "$archive" -C "$work" .
  done
fi
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
set --
if [ -n "$SIGN_IDENTITY" ]; then
  set -- --mac-sign --mac-app-image-sign-identity "$SIGN_IDENTITY" --mac-entitlements packaging/entitlements.plist
  if [ -n "$SIGN_KEYCHAIN" ]; then set -- "$@" --mac-signing-keychain "$SIGN_KEYCHAIN"; fi
fi
"$JDK/bin/jpackage" "$@" --type app-image --name diyalamp --input build/input --main-jar diyalamp.jar --main-class com.diyalamp.desktop.DesktopLauncher --runtime-image "$RUNTIME" --app-version "$VERSION" --vendor diyalamp --description 'Local Markdown editor for macOS. No account required.' --mac-package-identifier com.diyalamp.desktop --icon build/diyalamp.icns --java-options "--module-path=\$APPDIR/fx-deps/$FX_PLATFORM" --java-options '--add-modules=javafx.controls' --java-options '--enable-native-access=javafx.graphics,ALL-UNNAMED' --java-options '-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua' --dest "$APP_DEST"
TARGET="build/release/diyalamp-${VERSION}-macos-${ARCH}.dmg"
if [ -n "$SIGN_IDENTITY" ]; then
  codesign --verify --deep --strict "$APP_DEST/diyalamp.app"
  codesign -dvv "$APP_DEST/diyalamp.app" 2>&1 | grep -q '^Authority=Developer ID Application:'
fi
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
if [ -n "$SIGN_IDENTITY" ]; then
  codesign_id "$DMG"
  if [ -n "$NOTARY_ARGS" ]; then
    # Credentials contain no spaces: key paths are generated, IDs are UUIDs.
    # shellcheck disable=SC2086
    xcrun notarytool submit "$DMG" $NOTARY_ARGS --wait
    xcrun stapler staple "$DMG"
    xcrun stapler validate "$DMG"
  fi
fi
if [ -f "$TARGET" ]; then mv "$TARGET" "$TARGET.previous-$(date +%s)"; fi
mv "$DMG" "$TARGET"
(cd build/release && shasum -a 256 "$(basename "$TARGET")") > "$TARGET.sha256"
printf 'Ready: %s\n' "$TARGET"
