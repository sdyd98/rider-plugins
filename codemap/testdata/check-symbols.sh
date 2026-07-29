#!/usr/bin/env bash
# Checks what the ReSharper backend reports against what the fixture says it should.
#
#   ./codemap/testdata/check-symbols.sh
#
# Launches a sandbox Rider on testdata/cpp, has the plugin ask the backend about every fixture file, and
# compares the answer to the `//= 함수` / `//= 아님` marks in the sources. Prints every line the two
# disagree about and exits non-zero if there are any.
#
# This is the only automated check the backend has: its real caller sits behind a tool window and a file
# being open, which no script can drive.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
FIXTURE="$HERE/cpp"
OUT="${TMPDIR:-/tmp}/codemap-symbols.txt"
LOG="${TMPDIR:-/tmp}/codemap-check-symbols.log"
FILES="App/Net.h,App/Net.cpp,App/World.h,App/World.cpp"

cleanup() {
  pkill -9 -f "idea.plugins.path=$ROOT/.intellijPlatform" 2>/dev/null
  pkill -f "Rider.Backend.*--Internal" 2>/dev/null
}
trap cleanup EXIT

rm -f "$OUT"
cleanup
sleep 2

echo "샌드박스 기동 — 로그: $LOG"
(cd "$ROOT" && ./gradlew :codemap:runIde \
  -PrunIdeProject="$FIXTURE/Probe.sln" \
  -Dcodemap.symbolDump="$OUT" \
  -Dcodemap.symbolDumpFiles="$FILES" \
  > "$LOG" 2>&1) &

for _ in $(seq 1 60); do
  [ -s "$OUT" ] && break
  sleep 5
done

if [ ! -s "$OUT" ]; then
  echo "응답 없음 — 백엔드가 안 떴거나 솔루션이 안 열렸습니다. $LOG 를 보세요." >&2
  exit 1
fi

python3 - "$FIXTURE" "$OUT" <<'PY'
import re, sys, collections

fixture, dump = sys.argv[1], sys.argv[2]

# What the sources claim: line number -> expected ("함수" | "아님"), per file.
expected = collections.defaultdict(dict)
for rel in ("App/Net.h", "App/Net.cpp", "App/World.h", "App/World.cpp"):
    for n, line in enumerate(open(f"{fixture}/{rel}", encoding="utf-8"), 1):
        m = re.search(r"//=\s*(함수|아님)", line)
        if m:
            expected[rel][n] = m.group(1)

# What the backend answered.
reported = collections.defaultdict(dict)
current = None
for line in open(dump, encoding="utf-8"):
    if line.startswith("# "):
        current = line[2:].split()[0]
        continue
    if current and "\t" in line:
        n, kind, sig = line.rstrip("\n").split("\t", 2)
        reported[current][int(n)] = (kind, sig)

bad = 0
for rel in ("App/Net.h", "App/Net.cpp", "App/World.h", "App/World.cpp"):
    want = expected[rel]
    got = reported[rel]
    missing = [n for n, kind in want.items() if kind == "함수" and n not in got]
    spurious = [n for n in got if want.get(n) != "함수"]

    total = sum(1 for k in want.values() if k == "함수")
    print(f"\n{rel}: 기대 {total}개 / 응답 {len(got)}개")
    src = open(f"{fixture}/{rel}", encoding="utf-8").read().splitlines()

    for n in sorted(missing):
        print(f"  못 찾음  {n}: {src[n-1].strip()[:88]}")
        bad += 1
    for n in sorted(spurious):
        mark = want.get(n, "표식 없음")
        print(f"  오탐    {n} ({mark}): {got[n][1][:88]}")
        bad += 1

    # A declaration reported as a definition (or the reverse) is a wrong fact, not a missing one.
    for n, (kind, sig) in got.items():
        if want.get(n) != "함수":
            continue
        is_def = rel.endswith(".cpp") or "{" in src[n-1] or "= default" in src[n-1]
        if is_def and kind != "정의":
            print(f"  정의인데 선언  {n}: {sig[:80]}")
            bad += 1
        if not is_def and kind != "선언":
            print(f"  선언인데 정의  {n}: {sig[:80]}")
            bad += 1

print(f"\n{'일치' if bad == 0 else f'불일치 {bad}건'}")
sys.exit(1 if bad else 0)
PY
