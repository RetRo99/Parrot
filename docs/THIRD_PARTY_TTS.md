# Third-party text-to-speech models

## Supertonic 3

Supertonic 3 is developed by Supertone and distributed under the BigScience Open RAIL-M
license. The app downloads an INT8 conversion packaged by the sherpa-onnx project only after the
user reviews and accepts the model terms.

- Model source: https://huggingface.co/Supertone/supertonic-3
- Runtime package: https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
- License shipped in the app:
  `translations/src/commonMain/composeResources/files/supertonic_openrail_m_license.txt`

Supertonic speech is identified in the app as AI-generated. The app exposes only the model's fixed
voice styles and does not provide voice cloning or custom-speaker training.
