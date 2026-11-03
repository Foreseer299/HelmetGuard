#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/helmetguard-offline-tts"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
AAR_SHA="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-xiao_ya-medium-int8.tar.bz2"
MODEL_SHA="eab027e194e70289233cf12308373611fa4e2e96ef2d97354ef433ab663d831f"
MODEL_DIR="vits-piper-zh_CN-xiao_ya-medium-int8"

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
install -m 0644 "$TMP/$MODEL_DIR/zh_CN-xiao_ya-medium.onnx" "$ROOT/app/src/main/assets/offline_tts/$MODEL_DIR/model.onnx"
for file in lexicon.txt tokens.txt date.fst number.fst phone.fst MODEL_CARD; do
  install -m 0644 "$TMP/$MODEL_DIR/$file" "$ROOT/app/src/main/assets/offline_tts/$MODEL_DIR/$file"
done

cat <<'SUMS' | (cd "$ROOT/app/src/main/assets/offline_tts/$MODEL_DIR" && sha256sum -c -)
d4145488f47914614116e4f77338532d827cfccf5f5d7a58c15c5e54cc5434ba  model.onnx
003e76f89fec389ec109b557f47cc315a1ac31a99091f0cc9d15949f9024416b  lexicon.txt
e9e6d2838a2676749adbf0376c4cf3c3cd28eb27ed953dcc2adcab8db3c33ba7  tokens.txt
eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718  date.fst
743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd  number.fst
1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7  phone.fst
5720e170d3872dcea10803c77a4a6b4424a9af366431fdc5668adafd95e33f42  MODEL_CARD
SUMS

# Remove the superseded model so an existing checkout does not package two voices.
rm -rf "$ROOT/app/src/main/assets/offline_tts/vits-icefall-zh-aishell3"
echo "Embedded ARM64 offline TTS dependencies prepared."
echo "Review docs/THIRD_PARTY_TTS.md before distributing a production APK."
