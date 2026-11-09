"""Build isolated same-key/newer-version and wrong-key APKs for emulator updater tests."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

root = Path(__file__).resolve().parent.parent
os.chdir(root)
sdk = Path(os.environ["ANDROID_HOME"])
jdk = Path(os.environ["JAVA_HOME"])
java = jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
tools = sdk / "build-tools" / "36.0.0"
work = root / ".tools" / "updater-test-fixtures"
work.mkdir(parents=True, exist_ok=True)


def build(*properties):
    subprocess.run([str(java), "-Xmx128m", "-classpath", "gradle/wrapper/gradle-wrapper.jar",
                    "org.gradle.wrapper.GradleWrapperMain", ":app:assembleDebug", "--console=plain", *properties], check=True)


print("Building the normal app first...", flush=True)
build()
apk = root / "app/build/outputs/apk/debug/app-debug.apk"
shutil.copyfile(apk, work / "bootstrap.apk")
badging = subprocess.check_output([str(tools / ("aapt2.exe" if os.name == "nt" else "aapt2")), "dump", "badging", str(apk)], text=True, encoding="utf-8")
current_code = int(re.search(r"versionCode='(\d+)'", badging).group(1))
candidate_code = current_code + 1
print("Building a same-key update with version code", candidate_code, flush=True)
try:
    build("-PstilldayVersionCode=" + str(candidate_code))
    shutil.copyfile(apk, work / "good.apk")
finally:
    build()  # Restore the developer's real version; do not edit the Gradle source.

key = work / "fixture-only.jks"
if not key.exists():
    subprocess.run([str(jdk / "bin" / ("keytool.exe" if os.name == "nt" else "keytool")), "-genkeypair", "-keystore", str(key),
                    "-alias", "fixture", "-storepass", "stillday-test-only", "-keypass", "stillday-test-only", "-dname", "CN=Updater Test Fixture",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-noprompt"], check=True)
subprocess.run([str(java), "-jar", str(tools / "lib/apksigner.jar"), "sign", "--ks", str(key), "--ks-key-alias", "fixture",
                "--ks-pass", "pass:stillday-test-only", "--key-pass", "pass:stillday-test-only", "--out", str(work / "wrong-key.apk"), str(work / "good.apk")], check=True)

assets = root / "app/build/update-fixtures/updates"
assets.mkdir(parents=True, exist_ok=True)
for filename in ["good.apk", "wrong-key.apk"]:
    shutil.copyfile(work / filename, assets / filename)
manifest = {"schemaVersion": 1, "packageName": "dev.stillday.app", "versionCode": candidate_code,
            "versionName": re.search(r"versionName='([^']+)'", badging).group(1), "minSdk": 26,
            "apkName": "Stillday.apk", "sizeBytes": (work / "good.apk").stat().st_size,
            "sha256": hashlib.sha256((work / "good.apk").read_bytes()).hexdigest()}
(assets / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
print("Updater fixtures are ready. The normal app remains at version code", current_code, flush=True)
