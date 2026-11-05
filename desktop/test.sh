#!/bin/sh
set -eu
cd "$(dirname "$0")"
sh build.sh
. ./java-home.sh
mkdir -p build/test-classes
find src/test/java -name '*.java' | sort > build/test-sources.txt
"$JDK/bin/javac" --release 25 --module-path "build/fx-deps/$FX_PLATFORM" --add-modules javafx.controls -cp "build/classes:build/deps/*:build/fx-deps/common/*" -d build/test-classes @build/test-sources.txt
find src/test/java -name '*Tests.java' | sort | while read -r source; do
  test_name=$(printf '%s' "${source#src/test/java/}" | sed 's/\.java$//; s|/|.|g')
  case "$test_name" in
    com.diyalamp.desktop.fx.*) fx_test_flags='-Dglass.platform=Headless -Dprism.order=sw' ;;
    *) fx_test_flags='' ;;
  esac
  # Word splitting here is intentional: these are fixed, repository-owned VM flags.
  "$JDK/bin/java" --module-path "build/fx-deps/$FX_PLATFORM" --add-modules javafx.controls --enable-native-access=javafx.graphics -Djava.awt.headless=true $fx_test_flags -cp "build/classes:build/test-classes:build/deps/*:build/fx-deps/common/*" "$test_name"
done
