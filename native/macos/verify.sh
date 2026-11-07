#!/bin/sh
set -eu

dmg=${1:?Path to DiyaLamp-Native-Preview.dmg}
upstream_license=${2:-}
report=$(mktemp -d /private/tmp/diyalamp-native-editor-verification.XXXXXX)
mount="$report/image"
mkdir "$mount"
printf 'Verification directory: %s\n' "$report"

test "$(stat -f %z "$dmg")" -lt 5000000
(cd "$(dirname "$dmg")" && shasum -a 256 -c "$(basename "$dmg").sha256")
hdiutil verify "$dmg"
hdiutil attach -readonly -nobrowse -mountpoint "$mount" "$dmg"
detach() {
  status=$?
  trap - EXIT
  if ! hdiutil detach "$mount" >/dev/null && ! hdiutil detach -force "$mount" >/dev/null; then
    printf 'Could not detach verification image at %s\n' "$mount" >&2
    exit 1
  fi
  exit "$status"
}
trap detach EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

app="$mount/DiyaLamp Native Preview.app"
binary="$app/Contents/MacOS/DiyaLampNativeProbe"
license="$app/Contents/Resources/cmark-gfm-LICENSE.txt"
codesign --verify --deep --strict --all-architectures "$app"
lipo "$binary" -verify_arch arm64 x86_64
otool -arch all -L "$binary" > "$report/dependencies.txt"
cat "$report/dependencies.txt"
if grep -Ei '^[[:space:]]+[^[:space:]]*cmark' "$report/dependencies.txt"; then
  printf 'The mounted executable links cmark dynamically.\n' >&2
  exit 1
fi
test -s "$license"
grep -Fq 'Copyright (c) 2014, John MacFarlane' "$license"
grep -Fq 'Redistributions in binary form' "$license"
if [ -n "$upstream_license" ]; then
  cmp "$upstream_license" "$license"
fi

architectures=x86_64
if [ "$(uname -m)" = arm64 ]; then
  architectures=arm64
  if /usr/bin/arch -x86_64 /usr/bin/true 2>/dev/null; then
    architectures='arm64 x86_64'
  else
    printf 'Rosetta is unavailable. The Intel slice was checked but will not run here.\n'
  fi
fi
for architecture in $architectures; do
  fixture="$report/$architecture"
  mkdir "$fixture"
  python3 - "$architecture" "$binary" "$fixture" <<'PY'
from pathlib import Path
import subprocess
import sys

architecture, binary, folder_path = sys.argv[1:]
folder = Path(folder_path)
fixture = folder / 'Example.md'
original = b'\xef\xbb\xbf' + (
    '# Heading\n\n'
    'A **bold** and *italic* note with `code` and 日本語.\n\n'
    'See [label](https://example.test/a "Keep title").\n\n'
    '- [x] Finished ~~old~~ work\n\n'
    '> Quoted note.\n\n'
    '[[Reference note]]\n'
).replace('\n', '\r\n').encode('utf-8')
fixture.write_bytes(original)
(folder / 'Example.original.md').write_bytes(original)

stdout_path = folder / 'smoke.stdout.log'
stderr_path = folder / 'smoke.stderr.log'
with stdout_path.open('wb') as stdout, stderr_path.open('wb') as stderr:
    result = subprocess.run(
        ['/usr/bin/arch', '-' + architecture, binary, '--smoke', str(fixture)],
        cwd=folder, stdout=stdout, stderr=stderr, timeout=60,
    )
stdout = stdout_path.read_text(encoding='utf-8', errors='replace')
stderr = stderr_path.read_text(encoding='utf-8', errors='replace')
print(stdout, end='')
print(stderr, end='', file=sys.stderr)
if result.returncode != 0:
    raise SystemExit(f'{architecture}: app exited with status {result.returncode}')
if not any(line.startswith('PASS Native editor') for line in stdout.splitlines()):
    raise SystemExit(f'{architecture}: native editor success marker missing')
if not any(line.startswith('PASS Native folder') for line in stdout.splitlines()):
    raise SystemExit(f'{architecture}: native folder success marker missing')
print(f'{architecture}: exited normally')
PY
done
