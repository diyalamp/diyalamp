#!/bin/sh
set -eu
cd "$(dirname "$0")"
JDK="${DIYALAMP_JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || dirname "$(dirname "$(command -v javac)")")}"
mkdir -p build/deps build/classes
DEP=build/deps/flatlaf-3.7.2.jar
if [ ! -f "$DEP" ]; then
  curl --fail --location --proto '=https' --tlsv1.2 https://repo.maven.apache.org/maven2/com/formdev/flatlaf/3.7.2/flatlaf-3.7.2.jar -o "$DEP.tmp"
  mv "$DEP.tmp" "$DEP"
fi
printf '%s  %s\n' 917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4 "$DEP" | shasum -a 256 -c -
find src/main/java -name '*.java' | sort > build/sources.txt
"$JDK/bin/javac" --release 21 -cp "$DEP" -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
printf 'Class-Path: deps/flatlaf-3.7.2.jar\n' > build/manifest.mf
"$JDK/bin/jar" --create --file build/diyalamp.jar --main-class com.diyalamp.desktop.Diyalamp --manifest build/manifest.mf -C build/classes .
printf 'Built desktop/build/diyalamp.jar\n'
