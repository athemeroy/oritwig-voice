#!/usr/bin/env python3
"""Verified local rebuild receipts; build provenance, not an attestation."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,sys,tempfile,zipfile
ROOT=Path(__file__).resolve().parents[1]
ENGINE=ROOT/"engine/rnnoise"
AAR=ROOT/"app/libs/oritwig-rnnoise-proof-0.1.0.aar"
RECEIPT=ROOT/"app/libs/rebuild-receipt.json"
MANIFEST_SHA="5f1f35c92652c08a56e996a692ff00977dd6a688a9353c26c5d6d753dd69f4e7"
PIN="91c3012f2d1c6b357b879be725ca3df50bca50a5"
def sha(data):return hashlib.sha256(data).hexdigest()
def run(args,cwd=ENGINE):subprocess.run(args,cwd=cwd,check=True)
def contract():
    p=ENGINE/"provenance/build-inputs.json"
    if sha(p.read_bytes())!=MANIFEST_SHA:raise SystemExit("Pinned build-input manifest changed; review required")
    run([sys.executable,"tools/verify_source.py"])
    run([sys.executable,"tools/verify_build_inputs.py"])
    manifest=json.loads(p.read_text());inputs={"engine/rnnoise/"+k:v for k,v in manifest["files"].items()}
    inputs["engine/rnnoise/provenance/build-inputs.json"]=MANIFEST_SHA
    for name in ("tools/rebuild-engine.sh","tools/engine-build.py","app/build.gradle","build.gradle","settings.gradle","gradle.properties","gradle/wrapper/gradle-wrapper.properties","gradle/wrapper/gradle-wrapper.jar",".gitmodules",".github/workflows/android.yml"):
        inputs[name]=sha((ROOT/name).read_bytes())
    digest=sha(json.dumps(inputs,sort_keys=True,separators=(",",":")).encode())
    return inputs,digest,json.loads((ENGINE/"provenance/reviewed-android-native.json").read_text())
def verify():
    inputs,digest,review=contract()
    if not AAR.is_file():raise SystemExit("Build the verified local engine first: bash tools/rebuild-engine.sh")
    actual=sha(AAR.read_bytes())
    if actual==review["reviewed_aar_sha256"]:
        print("PASS: reviewed AAR plus unchanged pinned source/build inputs");return
    if not RECEIPT.is_file():raise SystemExit("Unrecognized AAR without a successful local rebuild receipt")
    r=json.loads(RECEIPT.read_text())
    if r.get("schema")!=1 or r.get("upstream_tree")!=PIN or r.get("aar_sha256")!=actual or r.get("source_inputs")!=inputs or r.get("inputs_digest")!=digest:
        raise SystemExit("Local engine receipt/input/output mismatch; rebuild from verified source")
    if r.get("normalized_native_sha256")!=review["normalized_native_sha256"] or r.get("non_native_entries_sha256")!=review["non_native_entries_sha256"]:
        raise SystemExit("Rebuilt native code/data or Java/assets differ from the reviewed engine")
    print("PASS: verified local rebuild receipt, complete pinned inputs and AAR SHA")
def build():
    inputs,digest,review=contract()
    missing=[name for name in ("ANDROID_NDK_HOME","JAVA_HOME") if not os.environ.get(name)]
    if missing:raise SystemExit("Set "+", ".join(missing)+" as documented in docs/TECHNICAL.md")
    ndk=Path(os.environ["ANDROID_NDK_HOME"]);java=Path(os.environ["JAVA_HOME"])
    properties=(ndk/"source.properties").read_text()
    if not any(line.strip()=="Pkg.Revision = "+review["ndk_revision"] for line in properties.splitlines()):raise SystemExit("Use pinned NDK "+review["ndk_revision"])
    cmake=subprocess.check_output(["cmake","--version"],text=True).splitlines()[0]
    javac_output=subprocess.check_output([str(java/"bin/javac"),"-version"],text=True,stderr=subprocess.STDOUT)
    javac=next((line.strip() for line in javac_output.splitlines() if line.startswith("javac ")),"")
    if cmake not in {"cmake version "+review["cmake_version"], "cmake version "+review["cmake_version"]+"-g37088a8"} or not javac.startswith("javac "+str(review["java_major"])+"."):
        raise SystemExit("Use pinned CMake 3.22.1 and JDK 21 for this verified engine build")
    # Emit no receipt unless all source, host/reference/JNI, Android and artifact checks pass.
    run(["bash","tools/test-host.sh"])
    run(["bash","tools/build-android.sh"])
    run([sys.executable,"tools/verify_android_artifacts.py"])
    built=ENGINE/"dist/oritwig-rnnoise-proof-0.1.0.aar"
    objcopy=ndk/"toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy"
    native={};nonnative={}
    with zipfile.ZipFile(built) as archive,tempfile.TemporaryDirectory(prefix="rnnoise-verify-") as tmp:
        tmp=Path(tmp)
        for name in archive.namelist():
            data=archive.read(name)
            if not name.endswith(".so"):nonnative[name]=sha(data);continue
            abi=name.split("/")[1];before=tmp/(abi+".so");after=tmp/(abi+"-normalized.so");before.write_bytes(data)
            run([str(objcopy),*review["normalization"],str(before),str(after)],cwd=ROOT)
            native[abi]=sha(after.read_bytes())
    if native!=review["normalized_native_sha256"] or nonnative!=review["non_native_entries_sha256"]:
        raise SystemExit("Rebuilt code/data/Java/assets differ from the reviewed AAR; no receipt emitted")
    # Recheck inputs after compilation, and only then atomically publish output plus receipt.
    final_inputs,final_digest,_=contract()
    if final_inputs!=inputs or final_digest!=digest:raise SystemExit("Build inputs changed during compilation")
    AAR.parent.mkdir(parents=True,exist_ok=True)
    temp=AAR.with_suffix(".aar.pending");shutil.copyfile(built,temp);temp.replace(AAR)
    receipt={"schema":1,"upstream_tree":PIN,"source_inputs":inputs,"inputs_digest":digest,"aar_sha256":sha(AAR.read_bytes()),"normalized_native_sha256":native,"non_native_entries_sha256":nonnative,"toolchain":{"ndk":review["ndk_revision"],"cmake":cmake,"javac":javac},"meaning":"Build provenance only; not cryptographic attestation or a bit-reproducible archive claim"}
    temp=RECEIPT.with_suffix(".json.pending");temp.write_text(json.dumps(receipt,sort_keys=True,indent=2)+"\n");temp.replace(RECEIPT)
    verify()
if __name__=="__main__":
    if len(sys.argv)!=2 or sys.argv[1] not in ("build","verify"):raise SystemExit("Usage: engine-build.py build|verify")
    (build if sys.argv[1]=="build" else verify)()
