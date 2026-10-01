# Verification and limits

## Passed

- Debug and release builds; both Android lint variants report no issues
- 9 JVM WAV contract tests: valid/tail-sized input, malformed/unsupported/truncated containers, bounded copy/cancel and maximum duration
- 44 actual API26 x86 ART checks: real JNI processing, six tail lengths, direct-JNI reference, original hashes, state/cancel cleanup, local persistence, rename/delete, both MediaPlayer variants starting, export equality and Activity recreation
- Actual system-picker supported import, 44.1 kHz stereo rejection, cancellation and successful WAV export
- Actual microphone explanation → deny → import still usable → grant → AudioRecord capture → Home stops/saves → reopen preserves the original
- Captured emulator WAV: 4,346,400 samples, 48 kHz mono PCM16, 90.55 seconds. Near-silent emulator input is not physical-microphone quality evidence
- Actual 9.685-second synthetic speech workflow: source/imported original hash e35a07abfa706c5d17c4ca4990dc8157d1a391721227fa18056b227163824703; app-cleaned/SAF-exported WAV hash d6e6a99fa3cf04972b824e0558aa2566d91d24114d57cb54bca0245e96f221a7; exactly 464,880 samples
- Source provenance: all 21 upstream files/model unchanged; four packaged native libraries byte-identical to the pinned AAR; APK requests only RECORD_AUDIO, with no INTERNET or storage permission; 16 KB ZIP alignment verifies

## Source-only publication build

The public app uses the immutable public engine submodule at 1d48b939677621baf0c197dcf95ac0c10de088f6. No prebuilt AAR is committed. A fresh source-only build passed host/reference/JNI checks, all four Android ABI builds, app/unit/lint checks and package validation. All four native libraries are byte-identical to the reviewed engine after stripping only debug sections and build-ID. The rebuilt Java classes, notices and other AAR entries also match. This does not claim bit-identical ZIP/signing/debug metadata. The delivered ac7…APK is unchanged.

The build receipt binds the complete pinned input set and output hash, is emitted only after successful checks, and is explicitly not a cryptographic attestation. The manual CI definition is included but remote CI has not been claimed as run.

## Reproduce

Use a recursive clone and run `bash tools/rebuild-engine.sh` before the Gradle commands in the main README, then `:app:assembleDebugAndroidTest`. Install both APKs on a test device and run:

```
adb shell am instrument -w dev.oritwig.voice.test/dev.oritwig.voice.VoiceInstrumentation
python3 engine/rnnoise/tools/verify_source.py
python3 tools/verify-package.py
```

`tools/make-spoken-demo.sh` creates the labeled speech/noise fixture with local FFmpeg/libflite; `tools/inspect-demo.py` inspects duration and levels using stock tools. Generated test files and machine-specific logs are not checked in. Generated comparison media is not an extra app import format.

## Real media

The screenshots are actual app screens. Workflow validation also used an actual screen/audio recording; a decoded comparison frame was visually inspected. A changing recording timer prevented one accessibility idle dump; active-capture evidence came from the real screenshot, saved state and resulting PCM file instead.

## Still open

Physical microphones, real-speech listening, Android 15/16 runtime and wider devices, Bluetooth/headset routing, phone interruptions, low-storage/provider failures, TalkBack/font scaling, battery/latency, production signing and distribution review. Synthetic noise-level measurements are not a perceptual quality score. The slow software-emulator processing interval is not a phone benchmark.
