#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/helmetguard-offline-tts"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
AAR_SHA="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2"
MODEL_SHA="ab468db3a3308cdd861495e0db2f25d79418a0c00639f74944c7cdf5dd8c6ec1"
MODEL_DIR="vits-icefall-zh-aishell3"

mkdir -p "$CACHE" "$ROOT/app/libs" "$ROOT/app/src/main/assets/offline_tts/$MODEL_DIR"
fetch() {
  local url="$1" dest="$2" sha="$3"
  if [[ ! -f "$dest" ]] || ! echo "$sha  $dest" | sha256sum -c - >/dev/null 2>&1; then
    rm -f "$dest"
    curl --fail --location --retry 3 --output "$dest" "$url"
  fi
  echo "$sha  $dest" | sha256sum -c -
}

fetch "$AAR_URL" "$CACHE/sherpa-onnx-1.13.8.aar" "$AAR_SHA"
fetch "$MODEL_URL" "$CACHE/$MODEL_DIR.tar.bz2" "$MODEL_SHA"
install -m 0644 "$CACHE/sherpa-onnx-1.13.8.aar" "$ROOT/app/libs/sherpa-onnx-1.13.8.aar"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
tar -xjf "$CACHE/$MODEL_DIR.tar.bz2" -C "$TMP"
for file in model.onnx lexicon.txt tokens.txt date.fst number.fst phone.fst speakers.txt; do
  install -m 0644 "$TMP/$MODEL_DIR/$file" "$ROOT/app/src/main/assets/offline_tts/$MODEL_DIR/$file"
done

cat <<'SUMS' | (cd "$ROOT/app/src/main/assets/offline_tts/$MODEL_DIR" && sha256sum -c -)
5511d651b7840c0a93a6bbfd4afd070a2c7f39ca1ec3ff2ecd73191519bbb852  model.onnx
ab2e61d357551e7b24ddd965d924aca784c20165ff58c150794e539c6b5e9e35  lexicon.txt
50b45a7b7de1752fd3c7b4755661c285f1547f59186eca2281089a81307ad953  tokens.txt
eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718  date.fst
743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd  number.fst
1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7  phone.fst
50a825fc9a89172eee04a4d68869d933d0eca0d847f3b6fcffff9514cc72442d  speakers.txt
SUMS

echo "Embedded ARM64 offline TTS dependencies prepared."
echo "Review docs/THIRD_PARTY_TTS.md before distributing a production APK."
