#!/usr/bin/env python3
"""Build emulator-only test fixture; requires ANDROID_HOME, HELMETGUARD_DEBUG_STORE."""
from pathlib import Path
import os, subprocess, zipfile
r=Path(__file__).resolve().parent
sdk=Path(os.environ['ANDROID_HOME']); store=os.environ['HELMETGUARD_DEBUG_STORE']
b=r/'build';(b/'classes').mkdir(parents=True,exist_ok=True)
subprocess.run(['javac','-encoding','UTF-8','-source','8','-target','8','-cp',str(sdk/'platforms/android-35/android.jar'),'-d',str(b/'classes'),str(r/'src/FixtureApp.java')],check=True)
subprocess.run(['bash',str(sdk/'build-tools/35.0.0/d8'),'--min-api','31','--lib',str(sdk/'platforms/android-35/android.jar'),'--output',str(b),*[str(p) for p in (b/'classes').rglob('*.class')]],check=True)
aapt=os.environ.get('AAPT2',str(sdk/'build-tools/35.0.0/aapt2'))
subprocess.run([aapt,'link','-I',str(sdk/'platforms/android-35/android.jar'),'--manifest',str(r/'AndroidManifest.xml'),'-o',str(b/'fixture.apk')],check=True)
with zipfile.ZipFile(b/'fixture.apk','a') as z:z.write(b/'classes.dex','classes.dex')
subprocess.run(['bash',str(sdk/'build-tools/35.0.0/apksigner'),'sign','--ks',store,'--ks-pass','pass:android',str(b/'fixture.apk')],check=True)
print(b/'fixture.apk')
