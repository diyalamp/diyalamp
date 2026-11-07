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
import select
import signal
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
if not any(line.startswith('PASS Native workspace search') for line in stdout.splitlines()):
    raise SystemExit(f'{architecture}: native search success marker missing')
print(f'{architecture}: exited normally')

def recovery_command(phase, note, store):
    return ['/usr/bin/arch', '-' + architecture, binary,
            '--recovery-smoke', phase, str(note), str(store)]

for scenario in ('restore', 'conflict', 'missing', 'symlink', 'saved'):
    case = folder / scenario
    case.mkdir(mode=0o700)
    note = case / 'Note.md'
    store = case / 'recovery.plist'
    note.write_bytes(original)
    with (case / 'edit.stderr.log').open('wb') as errors:
        with subprocess.Popen(recovery_command('edit', note, store), cwd=case,
                              stdout=subprocess.PIPE, stderr=errors) as writer:
            try:
                ready, _, _ = select.select([writer.stdout], [], [], 30)
                if not ready or writer.stdout.readline() != b'READY Native recovery checkpoint\n':
                    raise SystemExit(f'{architecture}: {scenario}: no recovery checkpoint')
                if scenario == 'restore':
                    second = subprocess.run(recovery_command('saved', note, store), cwd=case,
                                            capture_output=True, timeout=30)
                    if second.returncode == 0 or b'FAIL recovery setup' not in second.stderr:
                        raise SystemExit(f'{architecture}: second writer was allowed to start')
            finally:
                if writer.poll() is None:
                    writer.send_signal(signal.SIGKILL)
                writer.wait(timeout=10)
            if writer.returncode != -signal.SIGKILL or note.read_bytes() != original:
                raise SystemExit(f'{architecture}: crash test changed the original')

    edited = original + '\r\nRecovered Cafe\u0301 日本語 🪔.\r\n'.encode('utf-8')
    phase = scenario
    if scenario == 'conflict':
        note.write_bytes(b'External edit\n')
    if scenario == 'missing':
        note.unlink()
        phase = 'conflict'
    if scenario == 'symlink':
        outside = folder / 'Outside.md'
        outside.write_bytes(original)
        note.unlink()
        note.symlink_to(outside)
        phase = 'conflict'
    if scenario == 'saved':
        note.write_bytes(edited)
    result = subprocess.run(recovery_command(phase, note, store), cwd=case,
                            capture_output=True, timeout=30)
    (case / 'restart.stdout.log').write_bytes(result.stdout)
    (case / 'restart.stderr.log').write_bytes(result.stderr)
    if result.returncode or f'PASS Native recovery {phase}\n'.encode() not in result.stdout:
        print(result.stderr.decode(errors='replace'), file=sys.stderr)
        raise SystemExit(f'{architecture}: {scenario}: recovery restart failed')
    if scenario == 'conflict' and note.read_bytes() != b'External edit\n':
        raise SystemExit(f'{architecture}: recovery overwrote the external edit')
    if scenario == 'missing' and note.exists():
        raise SystemExit(f'{architecture}: recovery recreated the original')
    if scenario == 'symlink' and (not note.is_symlink() or outside.read_bytes() != original):
        raise SystemExit(f'{architecture}: recovery wrote through a symlink')
    if scenario in ('restore', 'saved') and note.read_bytes() != edited:
        raise SystemExit(f'{architecture}: recovered Markdown bytes changed')
    if phase == 'conflict' and (case / 'Recovered.md').read_bytes() != edited:
        raise SystemExit(f'{architecture}: recovered copy bytes changed')
    if store.exists():
        raise SystemExit(f'{architecture}: recovery snapshot was not cleared')
    print(f'{architecture}: passed crash recovery after {scenario}')
PY
done
