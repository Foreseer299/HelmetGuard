# Third-party offline TTS provenance and release gate

## Runtime

- Project: `k2-fsa/sherpa-onnx`
- Version: `1.13.8`
- License declared by upstream repository: Apache-2.0
- Official Android AAR: `sherpa-onnx-1.13.8.aar`
- Size: 50,129,134 bytes
- SHA-256: `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`
- Source: <https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8>

## Candidate Chinese voice

- Artifact: `vits-icefall-zh-aishell3.tar.bz2`
- SHA-256: `ab468db3a3308cdd861495e0db2f25d79418a0c00639f74944c7cdf5dd8c6ec1`
- Source: <https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models>
- Training dataset identified by sherpa-onnx documentation: AISHELL-3
- AISHELL-3 license published by OpenSLR: Apache-2.0
- Dataset source: <https://www.openslr.org/93/>

The downloaded model archive itself contains no `LICENSE`, `COPYING`, model card, or provenance file. An Apache-2.0 dataset and Apache-2.0 inference runtime do **not** by themselves prove that the released model weights were licensed for redistribution. Therefore:

1. The integration and deterministic download script may be used for local prototype verification.
2. A production/public APK containing these weights is blocked until the model publisher supplies an explicit weight license or a legally reviewed equivalent source is selected.
3. Do not describe the candidate weights as commercially cleared. Do not infer a voice-model license from the sherpa-onnx code license.
4. `scripts/prepare_embedded_tts.sh` deliberately records and verifies both archive and extracted-file hashes; it omits the 180 MB `rule.far` and packages only the model, lexicon, tokens, three small normalization FSTs, and speaker list.

## Runtime privacy behavior

The app has no `INTERNET` permission. The provider order is:

1. trusted system application + Chinese Voice + `isNetworkConnectionRequired=false` + no network feature;
2. bundled ARM64 sherpa-onnx candidate, when present;
3. fail closed with `TTS_FAILED`—never use a network Voice or cloud API.
