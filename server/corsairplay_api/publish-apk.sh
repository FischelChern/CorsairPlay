#!/bin/sh
# Publish an APK and generate the manifest consumed by CorsairPlay.
# Usage: publish-apk.sh /path/to/CorsairPlay-0.2.25.apk 0.2.25
set -eu

apk="$1"
version="$2"
[ -f "$apk" ] || { echo "APK not found: $apk" >&2; exit 1; }

config=/etc/corsairplay/config.json
apk_dir=$(python3 -c 'import json; print(json.load(open("/etc/corsairplay/config.json"))["apk_dir"])')
base_url=$(python3 -c 'import json; print(json.load(open("/etc/corsairplay/config.json")).get("public_base_url", "https://www.teamcc.net"))')
base=$(basename "$apk")

install -m 0644 "$apk" "$apk_dir/$base"
(cd "$apk_dir" && sha256sum "$base" > SHA256SUMS.txt)

python3 - "$version" "$base" "$apk_dir" "$base_url" <<'PY'
import json
import sys
from pathlib import Path

version, apk_name, apk_dir, base_url = sys.argv[1:5]
base_url = base_url.rstrip("/")
manifest = {
    "tag_name": version,
    "apk_name": apk_name,
    "apk_url": f"{base_url}/api/updates/{apk_name}",
    "checksums_url": f"{base_url}/api/updates/SHA256SUMS.txt",
}
Path(apk_dir, "manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
PY

echo "published $base"
echo "manifest: $apk_dir/manifest.json"
