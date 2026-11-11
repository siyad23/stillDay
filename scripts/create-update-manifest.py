"""Create GitHub release assets from the actual signed APK, never from a tag name."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def create_assets(apk: Path, output: Path, sdk: Path, release_tag: str | None = None, apk_name: str = "Stillday.apk") -> dict:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk", apk_name, re.IGNORECASE):
        raise ValueError("Use an APK filename without directories.")
    tools = sdk / "build-tools" / "36.0.0"
    aapt = tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
    signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    if not apk.is_file():
        raise ValueError("The APK does not exist.")
    # Verification also rejects unsigned release APKs before they can be published.
    subprocess.run([str(signer), "verify", str(apk.resolve())], check=True, capture_output=True, text=True)
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk.resolve())], text=True, encoding="utf-8")
    package_line = next(line for line in badging.splitlines() if line.startswith("package:"))
    values = dict(re.findall(r"(\w+)='([^']*)'", package_line))
    minimum = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.MULTILINE)
    if values.get("name") != "dev.stillday.app" or minimum is None:
        raise ValueError("Expected a signed Stillday APK with a declared minimum SDK.")
    version = values["versionName"]
    if release_tag is not None and release_tag != "v" + version:
        raise ValueError("The release tag must match the APK versionName: v" + version)
    output.mkdir(parents=True, exist_ok=True)
    target = output / apk_name
    if apk.resolve() != target.resolve():
        shutil.copyfile(apk, target)
    sha = hashlib.sha256(target.read_bytes()).hexdigest()
    manifest = {"schemaVersion": 1, "packageName": values["name"], "versionCode": int(values["versionCode"]),
                "versionName": version, "minSdk": int(minimum.group(1)), "apkName": target.name,
                "sizeBytes": target.stat().st_size, "sha256": sha}
    metadata = output / "update.json"
    metadata.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    metadata_sha = hashlib.sha256(metadata.read_bytes()).hexdigest()
    (output / "SHA256SUMS.txt").write_text(sha + "  " + target.name + "\n" + metadata_sha + "  update.json\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", default=Path("artifacts/release"), type=Path)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"), type=Path)
    parser.add_argument("--tag")
    parser.add_argument("--apk-name", default="Stillday.apk", help="Exact APK asset name on the GitHub release")
    args = parser.parse_args()
    if args.sdk is None:
        parser.error("Set ANDROID_HOME or supply --sdk.")
    print(json.dumps(create_assets(args.apk, args.output, args.sdk, args.tag, args.apk_name), indent=2))
