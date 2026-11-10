#!/bin/sh
set -eu
cd "$(dirname "$0")"
. ./java-home.sh
sh fetch-fx-deps.sh
mkdir -p build/deps
DEP=build/deps/flatlaf-3.7.2.jar
if [ ! -f "$DEP" ]; then
  curl --fail --location --proto '=https' --tlsv1.2 https://repo.maven.apache.org/maven2/com/formdev/flatlaf/3.7.2/flatlaf-3.7.2.jar -o "$DEP.tmp"
  mv "$DEP.tmp" "$DEP"
fi
printf '%s  %s\n' 917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4 "$DEP" | shasum -a 256 -c -
for artifact in commonmark commonmark-ext-gfm-strikethrough; do
  jar="build/deps/$artifact-0.32.0.jar"
  if [ ! -f "$jar" ]; then
    curl --fail --location --proto '=https' --tlsv1.2 "https://repo.maven.apache.org/maven2/org/commonmark/$artifact/0.32.0/$artifact-0.32.0.jar" -o "$jar.tmp"
    mv "$jar.tmp" "$jar"
  fi
done
printf '%s  %s\n' a7075291a1ff852df6e185963a0016d24913abf689f4f913d017d9ff19a14ac2 build/deps/commonmark-0.32.0.jar 77aafc2c0b4d11db6f8a38d01d20f78ec9095b39379e768fe2007708a71f8303 build/deps/commonmark-ext-gfm-strikethrough-0.32.0.jar | shasum -a 256 -c -
for specification in com/formdev/flatlaf-extras/3.7.2/flatlaf-extras-3.7.2 com/github/weisj/jsvg/2.1.0/jsvg-2.1.0; do
  filename=${specification##*/}
  if [ ! -f "build/deps/$filename.jar" ]; then
    curl --fail --location --proto '=https' --tlsv1.2 "https://repo.maven.apache.org/maven2/$specification.jar" -o "build/deps/$filename.jar.tmp"
    mv "build/deps/$filename.jar.tmp" "build/deps/$filename.jar"
  fi
done
printf '%s  %s\n' 7d03612d5c11d27a4ac714e79b11c73405d07f84a3214fe4ae091daadc8b5166 build/deps/flatlaf-extras-3.7.2.jar a538142d27c57c55066da02a535cc8eee3afd0d37b3daff8eba6344bd0ffc1b6 build/deps/jsvg-2.1.0.jar | shasum -a 256 -c -
# Rebuild generated classes/resources so removed assets cannot leak into a later package.
rm -rf build/classes
mkdir -p build/classes
find src/main/java -name '*.java' | sort > build/sources.txt
"$JDK/bin/javac" --release 25 --module-path "build/fx-deps/$FX_PLATFORM" --add-modules javafx.controls -cp "build/deps/*:build/fx-deps/common/*" -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
{
  printf 'Class-Path: \n'
  for dependency in build/deps/*.jar build/fx-deps/common/*.jar; do
    printf ' %s \n' "${dependency#build/}"
  done
} > build/manifest.mf
"$JDK/bin/jar" --create --file build/diyalamp.jar --main-class com.diyalamp.desktop.DesktopLauncher --manifest build/manifest.mf -C build/classes .
printf 'Built desktop/build/diyalamp.jar\n'
