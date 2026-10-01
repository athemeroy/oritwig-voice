#!/usr/bin/env python3
"""Verify vendored binary identity, APK libraries and no-network permissions."""
from pathlib import Path
import hashlib, subprocess, zipfile, json, os
root=Path(__file__).resolve().parents[1]
aar=root/'app/libs/oritwig-rnnoise-proof-0.1.0.aar'
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
expected='209062235d53d8018dbc6bc11e278cbe2b2ad69641bf663dabcb759c3847e13c'
subprocess.run([os.sys.executable,str(root/'tools/engine-build.py'),'verify'],check=True)
expected=hashlib.sha256(aar.read_bytes()).hexdigest()
with zipfile.ZipFile(aar) as a, zipfile.ZipFile(apk) as p:
    abis=['arm64-v8a','armeabi-v7a','x86_64','x86']
    for abi in abis:
        assert a.read(f'jni/{abi}/liboritwig_rnnoise_jni.so')==p.read(f'lib/{abi}/liboritwig_rnnoise_jni.so'), f'{abi} native core changed'
    assert 'assets/THIRD_PARTY_NOTICES.txt' in p.namelist()
sdk=Path(os.environ['ANDROID_HOME'])
aapt=sdk/'build-tools/35.0.0/aapt'
permissions=subprocess.check_output([str(aapt),'dump','permissions',str(apk)],text=True)
assert 'android.permission.INTERNET' not in permissions
assert 'android.permission.RECORD_AUDIO' in permissions
lines=[line for line in permissions.splitlines() if line.startswith('uses-permission:')]
assert len(lines)==1, lines
subprocess.run([str(sdk/'build-tools/35.0.0/zipalign'),'-c','-P','16','4',str(apk)],check=True)
print(json.dumps({'result':'PASS','aar_sha256':expected,'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'abis':abis,'permissions':lines,'native_libraries_byte_identical_to_aar':True,'zip_16k_alignment':True},indent=2))
