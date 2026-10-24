Sherpa-ONNX English keyword-spotting model
=========================================

Model: sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
Source: https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
License: Apache License 2.0 (see the model repository)

Only the int8 encoder/decoder/joiner, tokens.txt and the custom keywords.txt
are bundled here. keywords.txt contains the BPE token sequence for "JARVIS":
    ▁JA R VI S

The model is loaded through the bundled sherpa-onnx AAR. It is used only for
the wake-word path; the legacy Vosk speaker model remains available to the
existing voice-biometrics implementation.
