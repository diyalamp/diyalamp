#!/bin/sh
set -eu
cd "$(dirname "$0")"
. ./java-home.sh
sh build.sh
exec "$JDK/bin/java" --module-path "build/fx-deps/$FX_PLATFORM" --add-modules javafx.controls --enable-native-access=javafx.graphics,ALL-UNNAMED -jar build/diyalamp.jar "$@"
