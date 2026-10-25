WeSpeaker speaker-verification model
====================================

Model: wespeaker_en_voxceleb_CAM++ (English VoxCeleb, CAM++ architecture)
Source: https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_CAM%2B%2B.onnx
License: Apache License 2.0 (WeSpeaker / k2-fsa sherpa-onnx model release)

Bundled as a single ONNX file for fully offline owner verification. It runs in
the `:wake` process on the same 16 kHz PCM buffer that produced the "Jarvis"
keyword, so no second verification phrase and no network call are needed.

The legacy Vosk x-vector speaker model remains available as a fallback for
profiles enrolled before the WeSpeaker upgrade.
