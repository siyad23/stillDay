# Stillday agenda widget

Add it from **Settings → Home-screen widgets → Add agenda widget**, then accept the launcher's placement prompt. You can also long-press an empty area of the home screen, choose **Widgets → Stillday → Stillday agenda**, and drag it into place. The existing Add Quiz shortcut remains available separately.

The agenda groups today and the next six days by date. It shows upcoming/ongoing classes and labs, quizzes and exams, assignment and active-project deadlines, scheduled routines, and travel departures. Today's pending-task count follows the same project/focus rules as Today, including pending lab prework and reports. Tap the count to open Today, an event to open its details, the month to open Calendar, or **+** to add a quiz.

Cancelled classes and classes suspended during exams are omitted. Individual reschedules use their actual new date and time. Completed assignments/routines and paused projects are omitted. Provisional exams are labeled until the routine is entered. Deadlines remain visible during exam focus.

The widget follows Paper, Warm Sand, Charcoal, Midnight, or the system theme selected in the app. Its rounded background is 90% opaque, with lightly translucent cards and one muted accent. This lets the wallpaper show through without sacrificing legibility. It does not blur or read your wallpaper. No additional permission or network access is needed.

Drag the widget's resize handles to change its size; scroll inside the agenda to see later entries. It supports a minimum layout of 250 × 180 dp. Long names wrap to two lines. Up to 120 upcoming entries are included to keep the home-screen view bounded.

Saved changes, completed tasks, imports, resets and theme changes request an immediate widget refresh. Android also requests a refresh roughly every 30 minutes while the widget is placed. Boot/date/time/timezone changes request a refresh; Android's background scheduling may delay it. Dates use your configured university timezone, and time labels follow the phone's 12/24-hour setting.

Demo screenshots are captured from the real Pixel 7 emulator launcher using synthetic persisted records, not image mockups. See [TEST_REPORT.md](TEST_REPORT.md) for verification results.
