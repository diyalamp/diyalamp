package com.diyalamp.desktop.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Fixed, unprivileged replacement helper. Paths are arguments, never shell interpolation. */
final class InstallHelper {
  static final String SCRIPT = """
      #!/bin/sh
      set -eu
      test "$#" -eq 8
      parent_pid=$1
      installed=$2
      staged=$3
      backup=$4
      opener=$5
      version=$6
      architecture=$7
      expected_digest=$8
      rollback_if_missing() {
        if test ! -e "$installed" && test ! -L "$installed" && test -d "$backup"; then
          /bin/mv "$backup" "$installed" || true
        fi
      }
      trap rollback_if_missing EXIT
      trap 'rollback_if_missing; exit 1' HUP INT TERM
      attempts=0
      while kill -0 "$parent_pid" 2>/dev/null; do
        attempts=$((attempts + 1))
        if test "$attempts" -ge 120; then
          echo 'Update canceled: the app did not quit.'
          exit 1
        fi
        sleep 1
      done
      test -d "$installed" && test ! -L "$installed"
      test -d "$staged" && test ! -L "$staged"
      test ! -e "$backup" && test ! -L "$backup"
      test "$(/usr/bin/dirname "$installed")" = "$(/usr/bin/dirname "$staged")"
      test "$(/usr/bin/dirname "$installed")" = "$(/usr/bin/dirname "$backup")"
      "$installed/Contents/runtime/Contents/Home/bin/java" \
        -cp "$installed/Contents/app/diyalamp.jar" \
        com.diyalamp.desktop.update.UpdateVerifier "$staged" "$version" "$architecture" "$expected_digest"
      /bin/mv "$installed" "$backup"
      if ! /bin/mv "$staged" "$installed"; then
        /bin/mv "$backup" "$installed"
        "$opener" -n "$installed" || true
        echo 'Update failed; the previous app was restored.'
        exit 1
      fi
      if ! "$opener" -n "$installed"; then
        /bin/mv "$installed" "$staged"
        /bin/mv "$backup" "$installed"
        "$opener" -n "$installed" || true
        echo 'The new app could not be opened; the previous app was restored.'
        exit 1
      fi
      work=$(/usr/bin/dirname "$0")
      /bin/rm -f "$work/diyalamp-$version-macos-$architecture.dmg"
      echo "Updated diyalamp to $version. The previous app is retained as a backup."
      """;

  static Process launch(long parentPid, Path installed, Path staged, Path backup, Path work,
      String version, String architecture, String digest) throws IOException {
    Path helper = work.resolve("install-and-restart.sh");
    Files.writeString(helper, SCRIPT, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    return new ProcessBuilder(command(parentPid, installed, staged, backup, helper, version, Path.of("/usr/bin/open"), architecture, digest))
        .redirectErrorStream(true)
        .redirectOutput(work.resolve("install.log").toFile())
        .start();
  }

  static List<String> command(long pid, Path installed, Path staged, Path backup, Path script,
      String version, Path opener, String architecture, String digest) {
    return List.of("/bin/sh", script.toString(), Long.toString(pid), installed.toString(),
        staged.toString(), backup.toString(), opener.toString(), version, architecture, digest);
  }

  private InstallHelper() {}
}
