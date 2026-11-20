# In-app updates and signed GitHub releases

Stillday checks the latest public, non-prerelease release at [siyad23/stillDay](https://github.com/siyad23/stillDay/releases). There is no Play Store dependency, account, GitHub token in the app, or server to operate.

## On your phone

1. Install the updater-enabled APK once. Older app builds cannot acquire this feature by themselves.
2. Open **Settings → App updates** to check immediately. Automatic checks are enabled by default and run at most daily, when connected, including a background WorkManager check. Android can delay background work. Turn automatic checks off in the same section if desired.
3. Choose **Update now** to download. Stillday shows progress and verifies the size, SHA-256 checksum, package name, actual Android version code, Android compatibility, and the installed app's signing certificate.
4. The first time, enable **Allow from this source** for Stillday in Android's settings. Return to Stillday and confirm Android's native installation prompt. Installation retains the app's records.

Android confirmation is deliberately required. This is an in-app download/install flow, not an unattended silent update. No other app store is needed. Download cancellation, network errors and rejected APKs leave existing records untouched. A verified APK survives an app restart and is verified again before installation. An interrupted partial download restarts rather than resuming. If installation is interrupted, reopen Settings and retry.

Android or Play Protect may show additional checks or an unknown-developer warning for a sideloaded APK. Stillday does not disable or skip those system checks.

The public release available when this feature was implemented lacked `update.json`. Stillday explains that the release is not configured until a compatible release is published. It does not mistake an old release tag for a newer Android build. The current build uses **versionCode 22 / versionName 1.0.20**, so it can update the previous builds in place when signed with the same key. Subsequent updates must use a version code greater than 22.

## Choose and preserve the signing identity

Every update must use the **same signing key as the installed APK**. The updater rejects a different key and never offers to uninstall the app. Current development APKs use this laptop's Android debug key. GitHub's default debug key can differ between machines or runs, so ordinary debug CI artifacts are not a dependable release/update channel.

For ongoing distribution, choose a permanent release keystore and keep an encrypted backup of it and its passwords. Use Android Studio's **Build → Generate Signed App Bundle / APK → APK** wizard to create one if needed. Keep the keystore outside the repository. Do not send signing keys or passwords in chat or add them to Git.

If your phone already has the development build, a newly created release key will not update that installation. Either continue signing with its existing key, or export a backup in Stillday first and deliberately migrate to the permanent release build, then import the backup. Do not uninstall without a backup. Changing from a debug key to a new release key cannot preserve an in-place Android installation.

## One-time GitHub configuration

In the repository, open **Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The complete keystore encoded as Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | The signing key's alias |
| `ANDROID_KEY_PASSWORD` | That key's password |

To copy the Base64 value locally in PowerShell, replace the path below with your chosen keystore. The command puts it on the clipboard instead of printing it:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\private\stillday-release.jks')) | Set-Clipboard
```

Paste into the GitHub secret, then clear the clipboard. The workflow restores the key only in the runner's temporary directory and removes that file after the build. It uses GitHub's automatically provided `GITHUB_TOKEN` to create a draft release; no personal access token is required. Keep the repository public for unauthenticated update checks.

## Publish each update

1. Edit only the two version defaults in `app/build.gradle.kts`: **versionCode 22**, **versionName 1.0.20** for this release. Keep the `providers.gradleProperty(...)` expressions; change the values inside `getOrElse(...)`. A later release needs a higher code, for example 23 / 1.0.21. Documentation does not control the APK version.
2. **Commit and push all release changes before creating a tag.** A tag saves a particular commit; it never picks up later changes automatically. From the repository root:

   ```powershell
   python scripts/release-version.py --tag v1.0.20
   git add app/build.gradle.kts scripts .github/workflows docs/UPDATES.md docs/TEST_REPORT.md
   git commit -m "Prepare v1.0.20 build 22 and release checks"
   git push origin main
   python scripts/release-version.py --create-tag
   git push origin v1.0.20
   ```

   The helper refuses uncommitted changes, a different committed version, or an existing local tag. It creates only a local tag and never pushes or overwrites one. If other files are still modified, commit the intended changes first. In GitHub's tag picker, use the commit containing this version change. Re-running an old workflow uses the old tagged source.

3. Open **Actions → Signed APK release**. It first validates the tag against the checked-out version, then runs domain and app JVM tests and lint, builds a signed release APK using the validated version/code, verifies its signature and actual metadata, and creates a **draft** release containing `Stillday.apk`, `update.json`, and `SHA256SUMS.txt`.
4. Open **Releases**, review the draft's notes and APK, and select **Publish release**. Leave **Set as a pre-release** unchecked and make it the latest release. Drafts and prereleases are not offered by the app.
5. In the installed app, select **Settings → App updates → Check for updates** to test the published update immediately.

Tag/version-name disagreement, an unexpected APK build number, missing signing secrets, an unsigned APK or failing checks stops release creation. A reused tag/draft is not silently overwritten. Fix the failed run before retrying, or publish a fresh version/tag. GitHub builds and publication have not been executed from this local implementation session.

The regular **Android build** workflow still runs on pushes and pull requests without signing secrets. It verifies the source and provides a development APK; the signed release workflow provides a stable install/update identity.

## Manual release alternative

Build a signed APK with the same key and run this with JDK 17 and Android SDK 36 configured:

```powershell
python scripts/create-update-manifest.py --apk path/to/signed.apk --tag v1.0.20 --expected-version-code 22
```

Upload all three generated files from `artifacts/release/` to a published, latest GitHub release. The script extracts the real version code/name/minimum SDK from the signed APK and calculates its checksum; never hand-edit `update.json` to describe a different APK.

## Why v1.0.18 and v1.0.19 did not match

The local v1.0.18 tag points to commit `2c29d2e`, which declares 1.0.17 / build 19. The local v1.0.19 tag points to `bc7aee1`, which declares 1.0.18 / build 20. The later commit `c0120d8` declares 1.0.19 / build 21, but that does not move either existing tag. v1.0.20 must be tagged after the new version and workflow changes are committed. Existing tags are left intact.

## Verification

JVM tests cover release parsing, real version-code ordering and update policy. Device tests use disposable local fixtures to verify matching and mismatched signatures, truncation, checksum failure, cancellation, cache restoration, permission prompts, and network errors.

For reproducible real-APK device tests, start a Pixel 7 emulator, set `JAVA_HOME` / `ANDROID_HOME`, and run:

```powershell
python scripts/prepare-update-tests.py
.\gradlew.bat :domain:test :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

The fixture script temporarily builds a higher version through a Gradle property, restores the normal build, and generates a disposable test-only key under ignored `.tools/`. Generated APK fixtures are ignored build output. Real-APK tests report skipped when fixtures are absent. The permission-explanation UI test needs a fresh install with install-from-this-source permission still disabled; it skips when permission is already granted, because revoking it during instrumentation makes Android kill the running target. Native installation tests are explicitly opt-in because accepting the native installer replaces the running test target. See [TEST_REPORT.md](TEST_REPORT.md) for completed checks and device limits.

The security boundary is Android's signing identity, in addition to HTTPS and checksum checks. The current implementation requires the exact current signing-certificate set; signing-key rotation is not supported.

For the repaired public v1.0.11 release, duplicate drafts and signing-channel details, see [release repair](RELEASE_REPAIR.md). A current build 13 installation should now report that it is up to date.
