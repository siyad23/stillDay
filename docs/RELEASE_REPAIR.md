# Repairing the GitHub update channel

## What failed on 11 November 2026

The public v1.0.11 release contained Stillday-debug.apk and checksums, but no update.json. The successful signed-release workflow created a separate unpublished draft for the same tag, with Stillday.apk and update.json. The app intentionally checks only the latest public release. Consequently, build 13 displayed "The latest release is not set up for in-app updates yet."

The published APK and the draft APK also have different signing certificates. The draft cannot update an installation of the public debug APK in place. Publishing that draft as a substitute would introduce a second failure.

The repair adds update.json generated from the exact existing public APK, retaining its filename, version, size, checksum and signature. The APK is not replaced. Build 13 should now report that it is up to date; older compatible installations can discover build 13. Local unreleased builds do not become GitHub updates automatically.

## Preserve the update channel

- Publish the draft created by the Signed APK release workflow; do not create a separate release for the same tag or upload an unrelated development APK.
- Every update must use the signing key of the installation it is meant to update. A version-name change cannot overcome a signing-key mismatch.
- Keep update.json beside the exact APK named by its apkName field. Generate it from the APK, never by typing version codes or checksums.
- The workflow now stops if that tag already has a release. Existing releases need their assets repaired, rather than a second draft.
- The currently published debug channel and the permanent release-key drafts are distinct identities. Moving between those keys requires a deliberate backup and migration; never uninstall without a backup. Preserve both keys until all installations have migrated.

For an existing APK whose public asset name must remain unchanged:

```sh
python scripts/create-update-manifest.py --apk downloaded/Stillday-debug.apk --apk-name Stillday-debug.apk --tag v1.0.11 --output artifacts/repaired-release
```

Upload the resulting update.json to that existing release. Confirm its APK size and hash against the existing asset first. Do not overwrite a different manifest or APK blindly.

## Verification

Four Python tests check exact filename/bytes/checksums, the standard filename, tag mismatch and path rejection. An app JVM regression checks discovery with the original debug asset name and the equal-version decision. An optional live test runs the real GitHub transport against the published release:

```powershell
$env:STILLDAY_LIVE_UPDATES='true'
$env:STILLDAY_LIVE_VERSION_CODE='13'
$env:STILLDAY_LIVE_VERSION_NAME='1.0.11'
.\gradlew.bat :app:testDebugUnitTest --tests '*PublishedReleaseTest' --rerun-tasks
```

Set those two version values to the actual public release when testing a future release. Ordinary tests skip this network-dependent check.
