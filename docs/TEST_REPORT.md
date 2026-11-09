# Stillday 1.1 verification

Test date: 9 November 2026.

## Environment

- Windows laptop using hardware-accelerated Android Emulator (WHPX).
- Project-local Eclipse Temurin JDK 17, Gradle 8.13, AGP 8.13.2.
- Android 16 / API 36 x86_64 emulator configured as a Pixel 7 profile: **1080 × 2400 pixels, 420 dpi**.
- Application targets API 36, minimum API 26.

## Automated results

Final verification tasks (using the project-local JDK 17 and Android SDK):

```powershell
.\gradlew.bat :domain:test :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest
```

| Check | Result |
| --- | --- |
| Debug APK build and signing | Passed |
| Core JVM tests | 37 passed, 0 failures |
| Android UI/persistence/reminder/migration tests | 16 passed, 0 failures |
| Android lint | 0 errors; remaining notices concern newer dependency versions |
| Quiz entry stress check | Five consecutive complete chapter-selection/save flows passed within the UI test |
| Installed APK smoke test | Exercise/example solving and syllabus selection passed outside Gradle's install/uninstall lifecycle |
| In-place application update | Version 1.0.0 → 1.1.0 preserved the existing semester and saved quiz |
| Database migration | Version 1 → 2 preserved manually dated Semester 7, existing topic state and review history |

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
- `artifacts/screenshots/`: Pixel 7 screenshots, including light/dark themes, large text, manual Semester 7 setup, independently dated semesters, and exercise/example entry.
- `artifacts/test-results/`: XML results, build log, and lint report.
- `app/schemas/`: exported Room schemas 1 and 2.

## Practical limits

- Tests ran on an emulator, not a physical Pixel 7. Other Android API levels and OEM battery behavior were not exercised.
- The widget entry activity was tested; manual launcher pinning was not automated.
- Reminder scheduling, permission handling, and notification creation passed. Android's inexact alarms and battery policies can delay actual delivery; long-duration idle delivery was not measured.
- The current database version is 2, with a tested migration from version 1. Future upgrades must add and test explicit migrations; destructive migration is disabled.
- Exam capacity warnings are estimates. They do not guarantee retention or sufficient preparation when the available time is too small.
