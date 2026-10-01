#!/usr/bin/env python3
"""Inspect synthetic demo using stock FFmpeg/ffprobe; not a perceptual-quality test."""
import json,re,subprocess,sys,hashlib
from pathlib import Path
if len(sys.argv)!=3:raise SystemExit('usage: inspect-demo.py input.wav output.wav')
def inspect(path):
    meta=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_entries','stream=codec_name,sample_rate,channels,bits_per_sample,duration_ts,time_base','-of','json',path]))['streams'][0]
    levels={}
    for name,section in [('leading_synthetic_noise','0.1:0.5'),('speech_region','2:6'),('trailing_synthetic_noise','9.2:9.6')]:
        p=subprocess.run(['ffmpeg','-hide_banner','-nostdin','-i',path,'-af',f'atrim={section},astats=metadata=0:reset=0','-f','null','-'],capture_output=True,text=True,check=True)
        matches=re.findall(r'RMS level dB: ([-\w.]+)',p.stderr)
        levels[name+'_rms_dbfs']=matches[-1] if matches else None
    return {'path':path,'sha256':hashlib.sha256(Path(path).read_bytes()).hexdigest(),'audio':meta,'ffmpeg_levels':levels}
a,b=map(inspect,sys.argv[1:])
assert a['audio']['duration_ts']==b['audio']['duration_ts'], 'Sample count differs'
print(json.dumps({'input':a,'output':b,'exact_sample_count_preserved':True,'limits':'Artificial TTS plus fixed-seed pink noise. Level changes do not establish intelligibility, naturalness or real-microphone quality. No listening conclusion is asserted.'},indent=2))
