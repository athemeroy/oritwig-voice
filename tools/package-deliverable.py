#!/usr/bin/env python3
"""Package the validated local APK and reproducible source/evidence, never upload."""
from pathlib import Path
import hashlib,json,shutil,zipfile
root=Path(__file__).resolve().parents[1]
out=root/'dist';out.mkdir(exist_ok=True)
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
if not apk.exists():raise SystemExit('Build and validate the APK first')
shutil.copy2(apk,out/'OritwigVoice-debug.apk')
archive=out/'OritwigVoice-source.zip'
exclude={'.git','.gradle','build','dist','__pycache__'}
count=0
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for path in sorted(root.rglob('*')):
        relative=path.relative_to(root)
        if not path.is_file() or any(p in exclude for p in relative.parts) or relative.name=='local.properties':continue
        z.write(path,Path('OritwigVoice')/relative);count+=1
manifest={'app':'Oritwig Voice','source_file_count':count,'artifacts':[],'limits':'Debug-signed local prototype. Consult docs/VALIDATION.md for passed checks and outstanding device/listening validation.'}
for f in [out/'OritwigVoice-debug.apk',archive]:manifest['artifacts'].append({'file':f.name,'bytes':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()})
(out/'artifacts.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest,indent=2))
