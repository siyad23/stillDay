"""Check configured release versions, or safely tag an already committed release."""
import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def read_version(source):
    code = re.search(r'versionCode\s*=\s*providers\.gradleProperty\("stilldayVersionCode"\)\.map\(String::toInt\)\.getOrElse\((\d+)\)', source)
    name = re.search(r'versionName\s*=\s*providers\.gradleProperty\("stilldayVersionName"\)\.getOrElse\("([^"]+)"\)', source)
    if not code or not name:
        raise ValueError("Cannot read version defaults in app/build.gradle.kts.")
    code, name = int(code[1]), name[1]
    if not 1 <= code <= 2100000000 or not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', name):
        raise ValueError("Use a positive Android versionCode and a versionName such as 1.0.20.")
    return code, name


def validate_tag(tag, name):
    if tag != "v" + name:
        raise ValueError(f"Tag {tag} points to source declaring v{name}. Commit the version change BEFORE creating its tag. Use a fresh version/tag; do not overwrite a published tag.")


def create_tag(root, name, run=subprocess.check_output):
    def git(*args):
        return run(["git", *args], cwd=root, text=True).strip()
    if git("status", "--porcelain"):
        raise ValueError("Uncommitted changes found. Commit and push the release changes first, then run this command again. No tag was created.")
    committed = git("show", "HEAD:app/build.gradle.kts")
    configured = read_version((root / "app/build.gradle.kts").read_text(encoding="utf-8"))
    if read_version(committed) != configured or configured[1] != name:
        raise ValueError("The committed version differs from the working copy. No tag was created.")
    tag = "v" + name
    if git("tag", "--list", tag):
        raise ValueError(f"{tag} already exists. No tag was changed. Choose a fresh version instead.")
    git("tag", tag)
    return tag


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", help="Validate the GitHub release tag before building")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--create-tag", action="store_true", help="Create a local tag only after all changes are committed")
    args = parser.parse_args()
    try:
        code, name = read_version((ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"))
        if args.tag:
            validate_tag(args.tag, name)
        if args.create_tag:
            tag = create_tag(ROOT, name)
            print(f"Created local tag {tag}. Upload it with: git push origin {tag}")
        else:
            print(f"Stillday {name} / build {code} / release tag v{name}")
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                output.write(f"version_code={code}\nversion_name={name}\n")
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
