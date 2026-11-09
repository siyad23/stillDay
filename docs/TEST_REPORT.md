# Stillday verification

Test date: 9 November 2026.

Current development APK: version **1.0.1**, version code **3**, preserving the developer's version settings. The older 1.1 label below refers to the earlier feature verification, not the current Android version-code ordering.

## Environment

- Windows laptop using hardware-accelerated Android Emulator (WHPX).
- Project-local Eclipse Temurin JDK 17, Gradle 8.13, AGP 8.13.2.
- Android 16 / API 36 x86_64 emulator configured as a Pixel 7 profile: **1080 × 2400 pixels, 420 dpi**.
- Application targets API 36, minimum API 26.

## Automated results

Final verification tasks (using the project-local JDK 17 and Android SDK):

```powershell
.\gradlew.bat :domain:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest
```

| Check | Result |
| --- | --- |
| Debug APK build and signing | Passed |
| Core JVM tests | 48 passed, 0 failures |
| App JVM release-source tests | 9 passed, 0 failures |
| Android UI/persistence/reminder/migration/updater tests | 22 passed; 2 opt-in native-install tests skipped in the ordinary suite |
| Android lint | 0 errors; remaining notices concern newer dependency versions |
| Quiz entry stress check | Five consecutive complete chapter-selection/save flows passed within the UI test |
| Installed APK smoke test | Exercise/example solving and syllabus selection passed outside Gradle's install/uninstall lifecycle |
| In-place application update | Version 1.0.0 → 1.1.0 preserved the existing semester and saved quiz |
| Database migration | Version 1 → 2 preserved manually dated Semester 7, existing topic state and review history |
| Native in-app update | Same-key APK moved the installed app from version code 3 to 4 through Android's native Update prompt |
| Native update retention | Opt-in assertion passed: all prior entity records and preferences retained, with newly generated Today plan entries allowed |
| Signed release build | Passed with a disposable local test key; APK signature and generated metadata verified |
| GitHub workflows | Both workflows passed actionlint; no hosted build or release was published in this session |

## In-app updater

The six ordinary device tests use real generated APKs. They cover an accepted same-key newer build, rejection of a differently signed APK even with a matching checksum, truncated downloads, incorrect APK version metadata, corrupt content, cancellation cleanup, unchanged planner data after cancellation, daily check throttling, a verified cache restored after manager recreation and retained during a failed network check, the update prompt and unknown-source permission explanation, and usable Settings after a network failure.

The 20 new JVM tests cover version-code ordering independently of names/tags, package/version/size/checksum/certificate checks, Android compatibility, malformed metadata/JSON, allowed HTTPS hosts and repository ownership, drafts/prereleases, missing or legacy feeds, and mismatched GitHub asset metadata. Existing 37 domain tests and 16 device tests remain part of the full regression suite.

An intermediate repeat instrumentation run hit a Compose measure/layout re-entry exception on the network-error screen. The isolated UI rerun passed. The test now waits for the rendered error message, rather than only the manager's state. A separate run stopped because the permission-prompt test revoked installation permission while instrumenting; Android's log explicitly reported `REQUEST_INSTALL_PACKAGES changed` as its reason for killing the process. The test no longer revokes permission in the running target and requires a fresh install or permission revocation before instrumentation. The full regression suite was rerun after these harness corrections. These checks do not establish behavior on all other devices or API levels.

The two native-install tests are opt-in so ordinary tests never replace their own running target. The preparation test used the production verifier, PackageInstaller receiver, and foreground UpdateHost. Host automation then confirmed Android's **Update** prompt. Play Protect also presented an unknown-developer warning for the local debug APK; the per-install confirmation completed without disabling protection settings. Android reported installed version code 4. The subsequent retention assertion compared every previous entity record independent of database query order, confirmed prior plan entries and preferences survived, and permitted Today to create additional plan entries. The emulator was restored to the real development version code 3 afterward; no phone was used.

The signed-release simulation used a disposable fixture key solely under ignored `.tools/`; it is not a production signing key or the delivered APK's key. The metadata generator extracted the real package/version/minimum SDK and verified signatures on both release and debug APKs. `artifacts/release/` contains update assets for the delivered development APK, signed with this laptop's existing debug key. They are local preparation files and have not been uploaded. The current public release lacks the new update manifest, so a live GitHub-distributed upgrade remains dependent on publishing a correctly signed release with `update.json`.

A final smoke check used the installed app's real HTTPS transport against the public GitHub repository. Settings correctly displayed that the current release is not configured for in-app updates, with the latest check time and working controls. The full App updates section was visually checked at the Pixel 7 resolution; screenshot `20-live-github-check.png` records this result.

## Manual semesters and chapter problems

- Onboarding creates only the explicitly named semester. The device test starts at Semester 7 with start `2026-11-09` and expected end `2027-03-25`; no Semesters 1–6 or future semesters are generated.
- A separately created Semester 8 starts `2027-12-05` and ends `2028-04-11`. Semester 7 remains unchanged; no annual or fixed-duration offset is inferred.
- Repository tests verify that adding a semester preserves existing courses and topics, and that invalid end-before-start dates are rejected without changing saved data.
- Chapters have distinct Topics, Exercises and Example problems sections. Exercises/examples retain independent solve status, difficulty, understanding, partial progress and repetition state.
- The UI test verifies that bulk topic study leaves both problems Not started, solving the exercise leaves the example untouched, and a quiz syllabus can include all four chapter items.
- Core tests cover partial/failed/successful solves, review failures after a historical solution, mixed item workloads under the shared daily/difficulty caps, old-backup defaults, and serialization of problem state.
- The migration fixture recreates the exported version-1 schema and inserts actual topic/review rows before opening it with the new Room database. Schema validation and retained values both pass. No destructive migration is configured.

Core coverage includes weekly/alternating recurrence, stable occurrence IDs, rescheduling, recorded attendance, protected experiment records, leap-day/month-end routines, focus boundaries, class suppression, and explicit/inferred travel with meal overrides.

Study tests cover independent topic state, initial difficulty, all four understanding ratings, partial study progress, same-day deduplication, daily budget accounting, difficult-work limits, new/review mixing, course rotation, deferral priority, overlapping assessment coverage, shared topics, and capacity warnings. A seeded simulation runs 180 topics for 150 days with a two-week absence; all topics receive study time without automatic workload overflow.

Device tests also exercise course/topic creation, whole-chapter quiz syllabi, widget entry, attendance, occurrence rescheduling, project pausing, all four themes, activity recreation, 130% text scaling, Room round trips, backup history restoration, invalid/malformed import rejection, notification denial/grant handling, alarm registration, and disabling reminders.

The quiz automation initially exposed taps racing with keyboard/dialog resizing. The title field now offers Done, syllabus interactions clear focus, and the test waits for Android's keyboard helper before selecting coverage. Assertions check both the visible selection count and persisted topic links.

The prior 1.0 release also passed a manual reboot-persistence check after an emulator System UI interruption was dismissed. Its historical screenshot is `12-after-reboot.png`. For 1.1, the new checks focus on the in-place update and explicit database migration rather than repeating that reboot test.

## Artifacts

The paths below are local build/delivery outputs and are excluded from the source repository. Selected synthetic screenshots are committed under `docs/screenshots/`. GitHub workflow runs provide fresh APK and verification artifacts.

- `artifacts/Stillday-debug.apk`: installable development APK.
- `artifacts/release/`: the development APK as `Stillday.apk`, generated `update.json`, and checksums for a manual release using the existing development key.
- `artifacts/screenshots/`: Pixel 7 screenshots, including light/dark themes, large text, manual Semester 7 setup, independently dated semesters, and exercise/example entry.
- `artifacts/test-results/`: XML results, build log, and lint report.
- `app/schemas/`: exported Room schemas 1 and 2.

## Practical limits

- Tests ran on an emulator, not a physical Pixel 7. Other Android API levels and OEM battery behavior were not exercised.
- The widget entry activity was tested; manual launcher pinning was not automated.
- Reminder scheduling, permission handling, and notification creation passed. Android's inexact alarms and battery policies can delay actual delivery; long-duration idle delivery was not measured.
- The current database version is 2, with a tested migration from version 1. Future upgrades must add and test explicit migrations; destructive migration is disabled.
- Exam capacity warnings are estimates. They do not guarantee retention or sufficient preparation when the available time is too small.
