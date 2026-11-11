# Stillday verification

Latest test date: 11 November 2026 (Asia/Dhaka).

Current development APK: version **1.0.14**, version code **16**, with corrected dark themes and actual transparency in both home-screen widgets. Database schema remains 7. Earlier Drive and planner checks below are historical verification.

## Widget dark themes and real transparency

All **180 JVM tests pass (129 domain + 51 app)**; one optional live-GitHub check is skipped. Debug/release builds and lint pass.

The Pixel 7/API 36 widget run contains thirteen cases: **eleven passed and two opt-in fixtures skipped**. New checks render actual native drawables and sample pixel RGB/alpha for both widgets in every theme and both system modes. They verify cached System RemoteViews changing light/dark/light without regenerating colors, cached dark agenda rows and date chips, and dark initial layouts before provider loading. A Charcoal/Midnight native comparison is captured and visually inspected. Pixel assertions test alpha around 230/255 for outer backgrounds, 235/255 for activity cards and 224/255 for date chips, with opaque theme RGB rather than white-blended colors.

The real-host shortcut check replaces a legacy layout on the same widget ID, verifies re-inflation and its actual Add Quiz PendingIntent, then changes the saved app theme and waits for its text to update to Midnight. Existing populated/compact/busy agenda, overflow navigation, first placement, nonblocking startup and Binder-size checks also pass. The skipped cases are launcher capture and the isolated cold-process fixture. No new launcher screenshot or physical-device check is claimed for this change. See [widgets](WIDGETS.md) for the native comparison and compatibility details.


## Google Drive automatic backup

Current verification: **180 JVM tests passed (129 domain + 51 app)**; one optional live-GitHub check skipped. The 28 new sync/HTTP checks cover unchanged timestamps and Room ordering, metadata-only checks, offline retries, local-only and remote-only changes, simultaneous edits, both conflict choices, edits during upload/download, stale decisions, conditional If-Match updates, revision checks, private-folder pagination, expired authorization, missing files, malformed/oversized backups and newer-writer protection.

Pixel 7/API 36 device verification: **21 passed, 0 failures, 1 optional visual fixture skipped** across 22 cases. Seven new persistence checks cover real repository compare-and-set, import-journal restart recovery, later edits after an interrupted import, invalid-backup rejection, device-local Drive links and importable safety copies, and completing the Drive link after onboarding is disposed during restore. Two UI checks cover restore/create setup, local continuation, backup settings and a cancelled Android account-picker result. The first external-account test was nondeterministic because Google launched its own add-account activities; it was replaced with an instrumented cancelled picker result. The combined corrected run passes. Existing six-schema migrations, manual semester creation, weekday meals, problem attempts/backups and nonblocking widget startup pass in the same run.

Debug and locally signed release builds/lint pass. The local APK uses the existing laptop development signing key. Screenshots show synthetic records on the 1080 x 2400 / 420 dpi Pixel 7 emulator. Real Google account consent and a real Drive upload/download were **not exercised**; the HTTP tests use a fake server. The configured Cloud project's Android client must match the installed APK's package and SHA-1. See [Google Drive backup](GOOGLE_DRIVE.md) for setup, behavior, privacy and remaining live verification.


## Published GitHub update channel repair

The public v1.0.11/build 13 release was missing update.json; its complete signed assets were confined to a separate unpublished draft. Metadata generated from the exact public APK is now attached to the public release. The draft and public APK certificates differ, so the APK was retained rather than substituted. The live app transport verifies UpToDate for build 13 and Available for build 12. No phone installation or app data changes are required to repair this metadata error.

Current JVM verification: **153 passed (129 domain + 24 app)**, including the explicitly enabled network check. Four Python release-asset checks and actionlint also pass. The previously verified five targeted Pixel 7 UI checks remain applicable because this repair changes release configuration, metadata generation and tests; it does not change the app's production code. See [release repair](RELEASE_REPAIR.md).

## Weekday meals and problem attempts

Eight domain meal checks cover recurring Wednesday lunch without affecting nearby days or other meals, released planner time, one-date Eat/Skip/Outside/Different overrides, restored menus, legacy global settings and JSON round trips. The old global skip preference is ignored and omitted from new backups; existing weekday plans remain intact.

Five new problem checks cover a seven-minute completed solution, unfinished work beyond the provisional estimate, accumulated attempts, independent problems, failed-review spacing and invalid inputs. Both chapter and Today now share the solve-attempt dialog. The actual time entered counts toward the daily total; difficulty and the next-attempt estimate update future suggestions. Unfinished problems retain progress.

Five targeted device checks cover number-only exercise/example creation, solved and unfinished attempts, renaming without losing history, backup restoration, the Today recording path with actual time beyond its estimate, Wednesday-only lunch skips with a dated exception, activity navigation and existing problem/syllabus selection. One optional visual-system fixture is skipped. Two initial meal-test failures came from an ambiguous dropdown selector and tapping before the update snackbar cleared; both test synchronization issues were corrected.

All **151 JVM tests (129 domain + 22 app)** pass. Debug/release APK builds and lint are checked. The release-compiled local test APK uses the laptop's development key; no GitHub release was published. Pixel 7 screenshots use synthetic demo records. See [meal and problem tracking](MEALS_AND_PROBLEMS.md).

## Historical 1.0.11 widget verification

Both providers now use standard Android AppWidgetProvider and RemoteViews. Add Quiz has a native initial layout and direct PendingIntent; the agenda uses a bound RemoteViews collection service with individually loaded rows. Glance rendering dependencies have been removed. Refreshes go directly to the provider instead of enqueueing widget rendering jobs, and the provider names stay unchanged for existing placements. Displayed dates, themes, translucent backgrounds, category colors and event routes are preserved.

An earlier stress case reproduced a separate agenda failure: Android rejected an 883,736-byte update with TransactionTooLargeException. The widget retains its 40-entry and 160-code-point text bounds, and Open full calendar preserves access to overflow. Stored records are unchanged. Native collection delivery keeps the main update at **2,744 bytes**, below the 25,000-byte regression ceiling. Database loading runs on the collection Binder thread; service creation itself performs no database work.

Seven targeted device checks cover automatic first placement through the actual provider broadcast (without manually calling update), busy scrolling and overflow, populated and compact agendas, live theme/onboarding refresh on the same widget ID, Add Quiz entry via its real PendingIntent, the full-calendar PendingIntent, payload size, provider registration, and nonblocking service startup while the repository is locked. Two opt-in screenshot fixtures are skipped in the regular targeted run. The tests pass after a fresh signed release install on the Pixel 7/API 36 emulator. A separate cold-process check refreshes both placed widgets while asserting that no app activity is resumed. Both launcher screenshots were captured and visually inspected. Three JVM tests cover bounded entries, intact calendar/IDs/routes and safe Unicode truncation. All **146 JVM tests (124 domain + 22 app)** pass; both debug and release builds/lint are checked.

The full 48-check device regression run below belongs to 1.0.9. Database schema remains 7. The exact exception from the user's physical phone was not available; verification targets the reported first-add behavior in both widgets as well as the separately reproduced payload failure.

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
| Core JVM tests | Current 1.0.12: 129 passed, 0 failures |
| App JVM release-source/navigation/visual-contrast/legacy-backup/widget tests | 22 passed, 0 failures |
| Android UI/persistence/reminder/migration/updater/widget/navigation/lab/alarm/meal/exam tests | Previous 1.0.9 full suite: 48 passed, 0 failures, 6 opt-in skips; current widget fix: 7 targeted checks plus the isolated cold-process check passed |
| Android lint | 0 errors; 18 warnings and 3 hints, covering dependency updates and existing style suggestions |
| Quiz entry stress check | Five consecutive complete chapter-selection/save flows passed within the UI test |
| Installed APK smoke test | Exercise/example solving and syllabus selection passed outside Gradle's install/uninstall lifecycle |
| In-place application update | Version 1.0.0 → 1.1.0 preserved the existing semester and saved quiz |
| Database migrations | Paths from schemas 1, 2, 3, 4, 5 and 6 through schema 7 preserve records, menus, attendance, lab deadlines, per-occurrence rooms and routine exceptions |
| Historical native in-app update | Previous updater implementation moved a same-key test APK from version code 3 to 4 through Android's native Update prompt |
| Historical native update retention | Opt-in assertion passed: all prior entity records and preferences retained, with newly generated Today plan entries allowed |
| Historical signed release build | Passed with a disposable local test key; APK signature and generated metadata verified |
| GitHub workflows | Both workflows passed actionlint; no hosted build or release was published in this session |

## Per-slot rooms, exam routines and study overrides

Nineteen new domain tests cover independent slot rooms and explicit blanks; retained past classes, attendance and individual reschedules; theory-only mid/final validation; matching periods and dates; early manual focus, manual stop, automatic lead-in and expiry; resumed project allocation; next-paper new/review selection; exact-time same-day transitions; rescheduled and simultaneous papers; missing syllabus and unpublished routines; later-paper manual selection, daily expiry and completed-item exclusion; explicit difficult-cap overrides within the time budget; delayed exams past the expected semester end; and serialization defaults.

Two new app JVM tests verify old backup room conversion and preserve explicitly blank or different slot rooms. Three new device UI checks enter two slots with different rooms, enter and edit a theory paper's date/time/room/syllabus, verify Automatic/Start/Stop focus and Back behavior, choose a not-due review from a later paper, restore automatic suggestions, retain changes across recreation and backup, and resume project time after stopping focus. A schema-6 fixture verifies one-time room conversion, retained occurrence overrides and attendance, and persisted focus controls; all earlier migration fixtures now continue through schema 7.

The ordinary device run contains 54 unique cases: **48 passed and six opt-in fixtures skipped**, with no failures or errors. All three wake-alarm checks passed in this run. Gradle's progress display counts skip events twice; totals here come from the JUnit XML. The opt-in fixtures cover four screenshot sets and two native installer checks. The new exam screenshot fixture also runs separately with synthetic data.

## Historical activity visual system and meal defaults

Eight core checks cover everyday skipping across a full week, weekday Eat/Skip exceptions, dated Eat/Skip/Outside/Different overrides, retained meals at home, old serialized defaults and round-trip settings. Skipped meals reserve no time in the automatic day plan.

Two app tests check category consistency and text contrast for all activity categories in every light/dark/system theme. Category labels, primary text and supporting text meet the 4.5:1 contrast threshold against their tested surfaces. UI checks cover recurring skips, weekday exceptions, dated changes, backup retention and direct activity navigation. A schema-5 fixture preserves the existing weekly menu and dated overrides while introducing Follow default.

The earlier visual-system device suite contained 49 unique cases: 42 passed, five opt-in fixtures skipped, and two wake-alarm checks skipped without exact-alarm access. All three wake-alarm checks then passed in a separate permission-enabled run, giving 44 unique non-opt-in cases verified. The five opt-in fixtures were widget screenshots, day-planning screenshots, visual-system screenshots and two native installer checks. The visual-system fixture also passed separately and includes Paper, Charcoal, meal defaults, project progress, prayer entry, Calendar and 1.3× text on the 1080 × 2400 Pixel 7 emulator. The shared cards also appear in course schedules and the agenda widget. Quick-add quiz is in the toolbar so it does not cover activity cards.

## Day planning and Fajr alarms

The 26 new core checks cover end-time/duration conversion across midnight, retained routine history and overrides, exact 30-minute work boundaries, class/lab buffers, skipped and retimed meals, all five prayers, sleep conflicts, active/paused/completed projects, exam focus, travel, fully booked days, 600 competing topics, old serialized settings, agenda integration, timezone/DST handling, and 150 varied overlapping schedules. No generated work overlaps a fixed commitment or sleep. A minute-by-minute check across eighteen hours proves that clock advancement never changes the times or category of remaining work slots; only their remaining-minute totals decrease.

Two UI checks enter an overnight routine, move one occurrence with a different duration, edit prayer times/meal duration/study share/sleep target, and restore settings through backup plus a fresh Room read. Schema-4 fixtures preserve old routine completion/movement and lab deadlines while adding safe duration defaults.

Three wake-alarm device checks verify the Clock backup intent, actual AlarmManager replacement/cancellation and stale-trigger rejection, and delivery of an exact alarm into the foreground ringing service. The real trigger reached maximum alarm-stream volume, exposed snooze/dismiss controls, scheduled the next daily alarm and stopped on dismiss. These three checks were rerun successfully after the final disabled-alarm/snooze safeguards. Emulator audio is disabled, so physical speaker loudness was not measured.

The earlier day-planning suite had 45 unique cases: 41 passed and four intentionally opted out (widget screenshot fixture, day-planning screenshot fixture, and two native installer checks). The Gradle progress display counts some skip events twice; the JUnit XML contains the unique-case count.

## Lab submission deadlines

Prework and reports have independent optional due dates and times, stored in the university timezone. Their dates appear in the experiment summary, Today, Calendar and the agenda widget. Pending past deadlines remain visible; Submitted, Received and Not required remove active warnings independently. Class rescheduling/cancellation and exam suppression preserve explicit deadlines.

Six new domain tests cover independent due dates/statuses, precise overdue boundaries, terminal statuses, preserved deadlines after class changes, legacy undated suggestions, multiple experiments without duplicated widget entries, serialization and malformed-date rejection. A device UI test adds both deadlines, edits one, verifies Calendar routing, submits prework independently, and clears the report deadline while preserving the other recorded date.

Notification checks verify separate lead-time and due-time alarm IDs, withdrawal of already displayed completed-work alerts, cancellation of future alerts and rejection of stale delivery after deadline changes. A persistence test restores deadlines through a backup and a fresh Room read. The schema-3 fixture preserves existing experiment statuses and adds null deadlines; schema-1 and schema-2 fixtures also migrate through to schema 4.

Current regression totals: **86 JVM tests (71 domain + 15 app) and 35 device tests passed**, with three expected opt-in skips. Lint has zero errors, with 17 existing warnings and three hints. Tests ran on the Pixel 7 emulator; actual delayed alarm delivery under physical-phone battery policies was not measured.

## Lab types and linked projects

Creating a project-based lab immediately creates a linked project due at the semester's expected end date. Device UI tests create this course, verify its initial project, add another linked project with a milestone and TODO, find its task in Today, complete the task through Projects, and verify the link after activity recreation. A second device test uses an existing experiment-based lab and independently records Written prework and a Submitted report.

Seven domain tests cover legacy JSON defaults, new backup round trips, invalid project-course links, shared daily-task/focus behavior, safe type changes, retained experiment history and automatic project creation without duplication on course edits. A persistence test verifies linked projects, milestones, TODO completion and personal-project classification through backup replacement and a fresh Room read.

The schema-2 migration fixture populates an existing lab with attendance and prework/report statuses plus a personal project, milestone and completed TODO. Schema 3 retains every value and defaults the existing lab to Experiment-based and the project to Personal. The schema-1 fixture now tests the full 1 → 2 → 3 path. Schema validation passes; no destructive migration is configured.

The lab-type release passed **80 JVM tests (65 domain + 15 app) and 31 device tests**, with three expected opt-in skips. Its database schema 3 is retained in the exported schema history; current deadline results are recorded above.

## Back navigation, Projects and dates

Page navigation now stores an independent history for each main tab. Toolbar Back and Android Back pop the same history. Per-page saved state retains the course section, calendar date and scroll position. Reselecting the current main tab returns to its root; switching between main tabs retains their histories. External widget links have a meaningful root instead of an empty Back history. Resetting onboarding clears navigation history.

Four new device tests exercise attendance Present/Absent through both Back controls; restoration after activity recreation; the Course Schedule section and its previous scroll position; chapter, assessment, assignment and Settings return paths; opening Calendar from Today and restoring its selected date; and creating/editing a project with a human-readable finish date. Six JVM navigation tests cover complete ancestry, independent tab histories, external links, duplicate-page prevention and serialization/restoration.

Projects replaces Calendar in the main navigation bar. Its overview separates active and paused projects and shows completed TODOs, milestone counts, the next milestone and deadlines. Calendar remains available through Today's calendar icon and Calendar link. Exam focus continues to pause project suggestions without hiding deadlines.

Four date tests verify `10 Aug, 2026`, month/year boundaries, case-insensitive typed input, ISO storage compatibility, leap days, invalid-date rejection and device-locale independence. Shared labels also format course/semester dates, calendar fields, project/assignment deadlines, reminders, update-check timestamps and widget dates. Android's native picker keeps its own system layout; the app fields display the requested format after selection.

The navigation release passed **73 JVM tests and 27 device tests**, with three expected opt-in skips. That release did not change the database schema; current lab-type migration results are recorded above.

## Home-screen agenda widget

The widget was placed through **Settings → Home-screen widgets → Add agenda widget** and the real Pixel launcher's placement prompt. Screenshots show native launcher-hosted RemoteViews at 1080 × 2400, not mockups. Paper and Charcoal were visually checked against the same wallpaper; the wallpaper is visible through the rounded background. The widget also shares the app's Warm Sand, Midnight and system palettes through the same color-scheme function.

Launcher checks covered vertical resizing, scrolling forward to later quizzes and assignment deadlines, opening a class's attendance/reschedule screen, and using **+** to open the Add Quiz editor. Changing a synthetic TODO from pending to complete and selecting Charcoal refreshed the placed widget: its count changed from four to three. Screenshot fixtures wait for queued refresh work before instrumentation ends, because exiting instrumentation can terminate pending background rendering.

Six new deterministic agenda tests cover actual rescheduled times, cancelled/finished/ongoing classes, exam class suppression and focus pauses while keeping deadline warnings, completed tasks/skipped routines/paused projects, provisional exams across the year boundary, the seven-day window, stable ordering of simultaneous events, empty data, and independent lab prework/report counts. A device assertion verifies the registered provider, resize support, periodic refresh and picker preview. Its opt-in demo fixture also passed separately.

The widget's initial full regression contained **63 JVM tests (54 domain + 9 app)** and **23 passing device tests**, with three expected opt-in skips. Lint had zero errors; its 17 warnings and three hints concerned existing dependency/configuration notices. No database schema or personal-data migration was needed for this widget. Current regression totals are recorded above.

Screenshots: `agenda-paper-home.png`, `agenda-charcoal-home.png`, `agenda-compact-home.png`, and `agenda-scrolled-home.png` under `artifacts/screenshots/`. Curated Paper/Charcoal examples are also in `docs/screenshots/`. Physical-phone behavior and launchers other than Pixel Launcher were not tested; Android may delay background widget refreshes.

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
- `app/schemas/`: exported Room schemas 1, 2, 3, 4, 5, 6 and 7.

## Practical limits

- Tests ran on an emulator, not a physical Pixel 7. Other Android API levels and OEM battery behavior were not exercised.
- The widget entry activity was tested; manual launcher pinning was not automated.
- Reminder scheduling, permission handling, and notification creation passed. Android's inexact alarms and battery policies can delay actual delivery; long-duration idle delivery was not measured.
- The current database version is 7, with tested migrations from versions 1, 2, 3, 4, 5 and 6. Future upgrades must add and test explicit migrations; destructive migration is disabled.
- Exam capacity warnings are estimates. They do not guarantee retention or sufficient preparation when the available time is too small.
