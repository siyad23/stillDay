# Upload Stillday to GitHub

The repository contains source code, the Gradle wrapper, database schemas, tests, documentation, and selected screenshots. Local tools, emulator images, APKs, reports, personal backups and signing keys are excluded by `.gitignore`.

## First push

1. Create an empty repository named `stillday` on GitHub with the visibility you prefer. Leave GitHub's README, license, and `.gitignore` initialization options unchecked for the first push.
2. From this project folder, run the commands below. Replace `YOUR_USERNAME` with your GitHub username or organization.

```sh
git init -b main
git add .
git update-index --chmod=+x gradlew
git diff --cached --stat
git commit -m "Initial Stillday Android app"
git remote add origin https://github.com/YOUR_USERNAME/stillday.git
git push -u origin main
```

If Git has no author identity configured, set your preferred commit name and email for this repository before committing:

```sh
git config user.name "YOUR_NAME"
git config user.email "YOUR_COMMIT_EMAIL"
```

Use GitHub's authentication flow when prompted. The preparation step does not create a remote repository or choose a commit identity.

## Source ZIP alternative

Extract `artifacts/Stillday-github-source.zip`, then upload the extracted contents of its `stillday` folder using GitHub's file upload page. Include the hidden `.github`, `.gitignore`, `.gitattributes` and `.editorconfig` entries. Uploading the ZIP itself will store an archive rather than an extracted source repository.

The workflow invokes the wrapper with `bash`, so CI also works when a web upload drops executable permissions. For a local Linux/macOS build, run `chmod +x gradlew` first or invoke it with `bash ./gradlew`.

## Automated builds

The Android workflow runs on pushes to `main`, pull requests, and manual dispatch. It installs Java 17 and SDK 36, runs the domain and app JVM tests and Android lint, builds a debug APK, and uploads the APK and verification reports to the workflow run. No repository secrets or cloud backend are required. This catches regressions before distribution and makes each build reproducible from its Git commit.

Connected Android tests remain available locally with `:app:connectedDebugAndroidTest` and a running emulator; the hosted workflow does not start an emulator. The initial GitHub-hosted run occurs after upload.

## APK releases

Keep APK files outside Git history. The repository includes a **Signed APK release** workflow that creates a draft release on `v*` tag pushes, using your permanent signing key stored in GitHub Secrets. It attaches the APK, `update.json` and checksums required by the in-app updater. Review and publish the draft to make it available to phones.

Follow [In-app updates and signed GitHub releases](UPDATES.md) for the four signing secrets, version/tag rules, publication steps and existing-installation signing requirements. Local and CI debug builds can use different signing keys; keep the same signing identity for updates and export your data before any deliberate reinstall.
