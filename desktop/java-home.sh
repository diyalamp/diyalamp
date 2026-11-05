#!/bin/sh
# Source from a desktop build script after changing to this directory.
if [ -n "${DIYALAMP_JAVA_HOME:-}" ]; then
  JDK=$DIYALAMP_JAVA_HOME
elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JDK=$(/usr/libexec/java_home -v '25+' 2>/dev/null || true)
else
  JDK=${JAVA_HOME:-}
fi
if [ -z "${JDK:-}" ]; then
  JDK=$(dirname "$(dirname "$(command -v javac)")")
fi
JAVA_MAJOR=$("$JDK/bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 25 ]; then
  echo 'The desktop UI requires JDK 25 or newer. Set DIYALAMP_JAVA_HOME to that JDK.' >&2
  exit 1
fi
case "$(uname -s):$(uname -m)" in
  Darwin:arm64) FX_PLATFORM=mac-aarch64 ;;
  Darwin:x86_64) FX_PLATFORM=mac ;;
  Linux:x86_64) FX_PLATFORM=linux ;;
  *) echo 'No pinned JavaFX build for this platform.' >&2; exit 1 ;;
esac
