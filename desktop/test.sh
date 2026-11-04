#!/bin/sh
set -eu
cd "$(dirname "$0")"
sh build.sh
JDK="${DIYALAMP_JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || dirname "$(dirname "$(command -v javac)")")}"
mkdir -p build/test-classes
find src/test/java -name '*.java' | sort > build/test-sources.txt
"$JDK/bin/javac" --release 21 -cp "build/classes:build/deps/*" -d build/test-classes @build/test-sources.txt
for source in src/test/java/com/diyalamp/desktop/*Tests.java; do
  test_name=${source##*/}
  "$JDK/bin/java" -Djava.awt.headless=true -cp "build/classes:build/test-classes:build/deps/*" "com.diyalamp.desktop.${test_name%.java}"
done
