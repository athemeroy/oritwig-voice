#!/usr/bin/env python3
"""Synthetic QA inputs only. These do not represent speech or microphone quality."""
import math, random, struct, wave
from pathlib import Path
root=Path(__file__).resolve().parents[1]/'evidence/fixtures'
root.mkdir(parents=True,exist_ok=True)
rng=random.Random(731)
with wave.open(str(root/'Voice QA synthetic.wav'),'wb') as f:
    f.setparams((1,2,48000,0,'NONE','not compressed'))
    f.writeframes(b''.join(struct.pack('<h',int(5500*math.sin(i*2*math.pi*220/48000)+rng.randint(-1600,1600))) for i in range(240001)))
with wave.open(str(root/'Unsupported 44k stereo.wav'),'wb') as f:
    f.setparams((2,2,44100,0,'NONE','not compressed'))
    f.writeframes(bytes(44100*4))
(root/'README.txt').write_text('These are synthetic test fixtures, not human speech. They establish format handling, real RNNoise execution, original preservation and export equality. No microphone-quality, listening-quality or intelligibility conclusion follows.\n')
