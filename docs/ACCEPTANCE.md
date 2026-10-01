# Acceptance checklist

## Automated

- [x] Source provenance and model unchanged
- [x] Gradle debug/instrumentation builds
- [x] JVM contract tests
- [x] Lint clean
- [x] AAR/native binary identity and no-network APK permissions
- [x] On-device real JNI stream/tail/length tests
- [x] Original hash preservation through processing
- [x] Cancellation cleanup and failed-import behavior
- [x] Local library persistence, names, deletion and Activity recreation
- [x] Actual platform MediaPlayer original/cleaned playback

## System UI

- [x] Launch/library visual check
- [x] Import through actual Storage Access Framework picker
- [x] Reject unsupported 44.1 kHz stereo file
- [x] Process supported synthetic WAV; compare screen
- [x] Export through actual system picker; compare exported bytes
- [x] Picker cancellation returns to stable screen
- [x] In-app microphone explainer before Android permission request
- [x] Microphone denial leaves import usable
- [x] Emulator microphone capture starts, then Home stops/saves and reopening shows the original
- [x] Settings/About/license control reachable (native UI tests); notice asset verified in APK

## Still required on physical devices

Real speech/noise listening, actual microphone capture quality, multi-device and Android 15/16 runtime behavior, Bluetooth/headset routing, phone interruptions, low storage, document-provider failures, TalkBack/font scaling, battery/latency and production signing/distribution review.

Results and reproduction steps are mapped in VALIDATION.md. The device is an API26 software emulator; capture is not physical microphone validation. Live recording updates prevented one UIAutomator idle dump; the actual screenshot and subsequent saved state are the evidence for that screen.
