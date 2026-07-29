#!/usr/bin/env bash
# Fetches lib/rd/rider-model.jar out of the Rider SDK archive.
#
# rdgen needs the model *definitions* (com.jetbrains.rider.model.nova.*) to generate the protocol, and an
# installed Rider does not ship them — they exist only inside the SDK zip on JetBrains' repository. That zip
# is 3.5 GB and the jar is 0.8 MB compressed, so this pulls just that one entry with HTTP range requests
# rather than making every build download the whole SDK.
#
#   ./codemap/resharper/tools/fetch-rider-model.sh [version]
#
# Writes codemap/resharper/lib/rider-model.jar (git-ignored). One-time, like riderLocalPath.
set -euo pipefail

VERSION="${1:-2026.1.3}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/../lib/rider-model.jar"
URL="https://cache-redirector.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/rider/riderRD/$VERSION/riderRD-$VERSION.zip"

mkdir -p "$(dirname "$OUT")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The CDN in front of the repository refuses ranged requests; the storage host behind it accepts them.
REAL="$(curl -sIL --max-time 60 "$URL" | grep -i '^location:' | tail -1 | tr -d '\r' | sed 's/^location: //I')"
TARGET="${REAL:-$URL}"

SIZE="$(curl -sI --max-time 60 "$TARGET" | grep -i '^content-length:' | tail -1 | tr -dc '0-9')"
[ -n "$SIZE" ] || { echo "SDK 크기를 알 수 없습니다: $TARGET" >&2; exit 1; }
echo "SDK $VERSION — $((SIZE / 1000000)) MB, 필요한 항목만 받습니다"

# Tail first: the zip64 end-of-central-directory record says where the central directory lives.
curl -s --max-time 120 -r "$((SIZE - 2000000))-$((SIZE - 1))" "$TARGET" -o "$TMP/tail.bin"

python3 - "$TMP" "$TARGET" "$SIZE" "$OUT" <<'PY'
import struct, subprocess, sys, zlib

tmp, target, size, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]

def fetch(start, length, path):
    subprocess.run(["curl", "-s", "--max-time", "300", "-r", f"{start}-{start + length - 1}",
                    target, "-o", path], check=True)
    return open(path, "rb").read()

tail = open(f"{tmp}/tail.bin", "rb").read()
i = tail.rfind(b"PK\x06\x06")
if i < 0:
    sys.exit("zip64 EOCD 를 찾지 못했습니다")
cd_size, cd_off = struct.unpack_from("<QQ", tail, i + 40)

cd = fetch(cd_off, cd_size, f"{tmp}/cd.bin")

entry = None
i = 0
while i < len(cd) - 46:
    if cd[i:i + 4] != b"PK\x01\x02":
        i = cd.find(b"PK\x01\x02", i + 1)
        if i < 0:
            break
        continue
    nlen, elen, clen = struct.unpack_from("<HHH", cd, i + 28)
    name = cd[i + 46:i + 46 + nlen].decode("utf-8", "replace")
    if name == "lib/rd/rider-model.jar":
        csize, usize = struct.unpack_from("<II", cd, i + 20)
        lho, = struct.unpack_from("<I", cd, i + 42)
        # Anything that did not fit in 32 bits lives in the zip64 extra field, in this order.
        extra = cd[i + 46 + nlen:i + 46 + nlen + elen]
        wide, j = [], 0
        while j + 4 <= len(extra):
            hid, hsz = struct.unpack_from("<HH", extra, j)
            if hid == 1:
                wide = list(struct.unpack_from("<" + "Q" * (hsz // 8), extra, j + 4))
            j += 4 + hsz
        k = 0
        if usize == 0xFFFFFFFF: usize, k = wide[k], k + 1
        if csize == 0xFFFFFFFF: csize, k = wide[k], k + 1
        if lho == 0xFFFFFFFF: lho, k = wide[k], k + 1
        entry = (lho, csize, usize)
        break
    i += 46 + nlen + elen + clen

if entry is None:
    sys.exit("SDK 안에 lib/rd/rider-model.jar 이 없습니다")

lho, csize, usize = entry
raw = fetch(lho, csize + 2000, f"{tmp}/entry.bin")
if raw[:4] != b"PK\x03\x04":
    sys.exit("로컬 헤더가 아닙니다")
nlen, elen = struct.unpack_from("<HH", raw, 26)
data = raw[30 + nlen + elen:][:csize]
jar = zlib.decompress(data, -15)
if len(jar) != usize:
    sys.exit(f"크기가 맞지 않습니다: {len(jar)} != {usize}")
open(out, "wb").write(jar)
print(f"{out} — {len(jar) / 1e6:.2f} MB")
PY
