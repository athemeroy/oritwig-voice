# Technical reference

Detailed source identity, reuse boundary, stream policy and build/reproduction notes for Oritwig Voice. For the product overview, see the [main README](../README.md).

## Origin, authorship and integrity

RNNoise is authored by Xiph/Mozilla and its contributors. It is an actual Telegram dependency, **not Telegram-authored RNNoise**. The Telegram client is not included.

- Source: https://github.com/DrKLO/Telegram/tree/f2908b14133bbffbf7ab04f641ecb5bfaf533242/TMessagesProj/jni/voip/rnnoise
- Commit: `f2908b14133bbffbf7ab04f641ecb5bfaf533242`
- Subtree: `91c3012f2d1c6b357b879be725ca3df50bca50a5`
- Complete snapshot: 21 unchanged files, 536,397 bytes
- Default model weights and core source patches: **zero**
- Vendored AAR SHA-256: `209062235d53d8018dbc6bc11e278cbe2b2ad69641bf663dabcb759c3847e13c`

`engine/rnnoise` contains the full extracted source, original licenses, source-hash manifest, actual Telegram-use references, build/JNI adapter and engine tests. `app/libs` contains the four-ABI AAR produced from it. The engine's README describes the bounded engine proof; this README documents the app shell added on top. No Telegram account, session, network library, WebRTC stack, API token or paid service is used.

## Reuse boundary

| Layer | Responsibility |
| --- | --- |
| Unchanged upstream RNNoise C + model | Analysis, FFT, pitch, inference, spectral gains, synthesis, all denoising |
| Existing engine extraction adapter | State creation/destruction, serialization, PCM16 conversion, Java/JNI bridge |
| New `SpeechProcessor` | Stream files through that API; zero-pad/flush/trim; progress and cancellation |
| New `WavIO` | RIFF validation/framing and PCM byte copying only; no audio codec, DSP, filter, resampler or denoiser |
| Android platform | Microphone capture, playback decoding, system document selection and export |
| New Android shell | Local library, names, UI, permission/error/cancel states and settings |

### Tail and delay policy

RNNoise's unchanged `denoise.c` uses 480-sample analysis history plus overlapping synthesis. For each independent file:

1. Create one native state and keep it for all frames
2. Zero-pad the final partial input frame to 480 samples
3. Process one additional zero frame to flush the one-frame overlap delay
4. Omit the first 480 output samples and emit exactly the original sample count

This is framing/delay management, not a modification of the engine. On-device tests compare the file adapter against separate direct JNI calls for 1, 479, 480, 481, 1,201 and 144,000 samples. Tiny clips are accepted but are not evidence of useful speech cleanup.

## Build

Prerequisites: JDK 17+ (tested 21), Android SDK 35 and build tools 35.0.0. The Gradle 8.11.1 wrapper verifies its distribution SHA-256. Source-only builds require the official Linux x86_64 SDK toolchain: JDK21, Android SDK35/build-tools35.0.0, NDK27.2.12479018 and SDK CMake3.22.1 (including Ninja), plus Python3. Android Gradle Plugin 8.9.3. The source app has no third-party Java/UI dependency; the only runtime dependency is the locally built RNNoise AAR. The public engine submodule is pinned to 1d48b939677621baf0c197dcf95ac0c10de088f6. Clone recursively; no private repository or credential is needed. JUnit 4.13.2 is test-only.

```sh
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmake/3.22.1/bin:$PATH"
git submodule update --init --recursive
bash tools/rebuild-engine.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
python3 engine/rnnoise/tools/verify_source.py
python3 tools/verify-package.py
```

Minimum Android 8.0 / API 26, target/compile API 35. The APK includes arm64-v8a, armeabi-v7a, x86_64 and x86. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. It is development-signed and not a store release.

`tools/rebuild-engine.sh` creates required output directories, verifies every upstream byte and every pinned integration/build input, runs host/reference/JNI tests, builds all four ABIs, and verifies their exports/dependencies/alignment. It compares normalized native bytes with the reviewed engine after removing only debug sections and build-ID; code and data must match exactly. Java classes and other AAR entries must also match their reviewed hashes. Only a successful build emits `app/libs/rebuild-receipt.json`, binding all source/build inputs and the produced AAR SHA.

Gradle rejects unknown AARs without a matching receipt; the original reviewed AAR hash remains recognized. The receipt is **build provenance, not a cryptographic attestation or a bit-reproducible-archive claim**. Debug/build paths can change raw library/AAR/APK hashes. No DSP, model, JNI runtime or app runtime source was changed to enable source-only rebuilding. The delivered debug APK remains ac7dc72d88ddd4183aa26efa1dadd80b4a382a5c28e3e8917281391ec0d51abc.

The manual-only CI workflow clones recursively and performs build/tests; it publishes no media or artifacts. The corresponding-source bundle includes resolved engine contents, so it needs no sibling project or submodule download; skip the Git clone/update steps when using that bundle.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.oritwig.voice.test/dev.oritwig.voice.VoiceInstrumentation
```

Tests generate clearly named synthetic fixtures. The app itself ships with no fake/demo recordings.

## Privacy and storage

The final APK requests only `RECORD_AUDIO`. It has no `INTERNET`, storage, advertising or telemetry permission and no backend. Microphone permission is optional; import works without it. Files live in private app storage, and Android backup/device transfer are disabled. Exports go only to the destination the user chooses; a third-party document provider may upload the export according to its own settings. Uninstalling removes local recordings. A user-triggered delete dialog explains permanent local deletion and leaves previously exported files alone.

Capture is foreground-only, not a background service. Navigating away stops and saves. A killed process or interrupted storage transaction may lose an unfinished take; completed originals remain safe. The app performs serial bounded streaming jobs, and cancelled/failed processing removes partial cleanup output. Interrupted pending cleanup is never shown as a completed version.


## Evidence and limits

See [VALIDATION.md](VALIDATION.md) for test evidence and [ACCEPTANCE.md](ACCEPTANCE.md) for the device checklist. The original RNNoise files and their license notices are preserved under `engine/rnnoise/vendor/rnnoise`.
