#!/usr/bin/env bash
# Optional local QA/demo fixture. Requires existing FFmpeg with libflite.
# This tool is never packaged in the app and performs no network request.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p evidence/fixtures
printf '%s\n' 'This is a synthetic voice test. The original recording stays on this device. Compare the two versions before exporting the one you prefer.' > evidence/fixtures/spoken-demo-text.txt
ffmpeg -hide_banner -nostdin -y -f lavfi -i "flite=textfile=evidence/fixtures/spoken-demo-text.txt:voice=slt" \
  -af 'aresample=48000,adelay=750,apad=pad_dur=1' -ar 48000 -ac 1 -c:a pcm_s16le -map_metadata -1 \
  'evidence/fixtures/Synthetic speech reference.wav'
ffmpeg -hide_banner -nostdin -y -i 'evidence/fixtures/Synthetic speech reference.wav' \
  -f lavfi -i 'anoisesrc=sample_rate=48000:amplitude=0.035:color=pink:seed=731' \
  -filter_complex '[0:a][1:a]amix=inputs=2:duration=first:normalize=0' -ar 48000 -ac 1 -c:a pcm_s16le -map_metadata -1 \
  'evidence/fixtures/Synthetic speech with noise.wav'
ffprobe -v error -show_entries format=duration:stream=codec_name,sample_rate,channels,bits_per_sample,duration_ts,time_base -of json 'evidence/fixtures/Synthetic speech with noise.wav'
