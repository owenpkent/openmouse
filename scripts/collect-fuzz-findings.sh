#!/bin/sh
# Collects the reproducers a Jazzer fuzz run left behind, with their paths, into
# an output directory, for the nightly fuzz workflow's report job.
#
# Jazzer writes a reproducer next to the seed corpus of the test that failed
# (app/src/test/resources/**/<Target>Inputs/<method>/crash-<hash>), or, when it
# cannot find that directory, into the working directory (app/). A finding is
# any such file that git does not track: the committed seeds under the same
# directories are regression tests, not findings. Files are found with `find`,
# not `git status`, because .gitignore patterns must not decide what gets
# reported (an unanchored `crash-*` once hid every reproducer).
#
# Usage:
#   scripts/collect-fuzz-findings.sh <out-dir>     copy findings into <out-dir>, print each
#   scripts/collect-fuzz-findings.sh --self-test   plant a crash and a timeout file,
#                                                  check both are collected, clean up
# Exit 0 when the collection ran (findings or not); the self-test exits 1 when a
# planted file was missed.
set -eu

cd "$(git rev-parse --show-toplevel)"

SEEDS=app/src/test/resources
PATTERN='.*/\(crash\|timeout\|slow-unit\|oom\)-[0-9a-fA-F]*$'

# Every candidate file, one per line: under the seed corpus at any depth, and
# directly in the module directory.
candidates() {
  find "$SEEDS" -type f -regex "$PATTERN" 2>/dev/null || true
  find app -maxdepth 1 -type f -regex "$PATTERN" 2>/dev/null || true
}

collect() {
  out=$1
  mkdir -p "$out"
  count=0
  candidates | while IFS= read -r f; do
    # Tracked means committed on purpose (a seed or a kept regression input).
    if git ls-files --error-unmatch -- "$f" > /dev/null 2>&1; then
      continue
    fi
    mkdir -p "$out/$(dirname "$f")"
    cp "$f" "$out/$f"
    echo "finding: $f"
  done
}

if [ "${1:-}" = "--self-test" ]; then
  dir="$SEEDS/io/github/owenpkent/openmouse/fuzz/SelfTestFuzzTestInputs/selfTest"
  crash="$dir/crash-0123456789abcdef"
  timeout="app/timeout-fedcba9876543210"
  tmp=$(mktemp -d)
  cleanup() { rm -rf "$tmp" "$crash" "$timeout"; rmdir "$dir" "$(dirname "$dir")" 2>/dev/null || true; }
  trap cleanup EXIT
  mkdir -p "$dir"
  printf 'crash' > "$crash"
  printf 'timeout' > "$timeout"
  found=$(collect "$tmp/findings")
  echo "$found"
  status=0
  for planted in "$crash" "$timeout"; do
    if [ ! -f "$tmp/findings/$planted" ]; then
      echo "self-test: $planted was not collected" >&2
      status=1
    fi
  done
  [ "$status" -eq 0 ] && echo "self-test: both planted reproducers collected"
  exit "$status"
fi

if [ -z "${1:-}" ]; then
  echo "usage: $0 <out-dir> | --self-test" >&2
  exit 2
fi
collect "$1"
