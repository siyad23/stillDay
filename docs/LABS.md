# Project-based and experiment-based labs

In **University → your semester → Add course**, or **Edit course**, choose one of:

- **Theory**
- **Experiment-based lab**
- **Project-based lab**

A project-based lab immediately creates a linked project with the course name and the semester's expected end date. It appears in **Projects** with a lab-course label. Open it to rename it, change its finish date, add milestones and TODOs, or pause its daily activities. The same work is available under **Course → Work → Lab projects**; these are two views of the same records.

You can add further projects from a project-based lab's Work section. **Projects → Add project → Project for** also lets you choose a project-based lab or **Personal project**. A lab project's details link back to its course. Tasks follow the usual active/paused and exam-focus rules; lab-project deadlines appear in Calendar and the agenda widget.

Experiment-based labs retain per-occurrence experiments with separate prework/report statuses: Not done, Written, Submitted, Received and Not required. Both kinds of lab retain schedules, individual rescheduling and attendance.

Each experiment also has independent **Prework submission date/time** and **Report submission date/time** fields. Open an experiment from the lab's Schedule or Work section to set them. Dates use `10 Aug, 2026`; times use the configured university timezone. Time defaults to 23:59. A blank date means no deadline, and clearing it removes only that submission's deadline.

Pending deadlines appear in Today, Calendar and the agenda widget. Overdue submissions remain visible. Written work still needs submitting; Submitted, Received and Not required stop deadline warnings independently for prework and report. Without a deadline, the existing class-based daily suggestions continue.

With reminders enabled, Stillday schedules an alert using the configured deadline lead time and another at the deadline. Submitting work or changing/removing its deadline cancels stale alerts, including already displayed lab alerts. Android's inexact alarm and battery policies can delay delivery.

Rescheduling, cancelling or suspending a class does not silently move or cancel an explicitly entered submission deadline. Clear that date or mark the item Not required when a submission is no longer needed.

Existing labs are treated as experiment-based when upgrading or importing older backups. The database migration adds the lab type and optional project-course link without deleting records. Old personal projects stay personal.

Changing an experiment-based lab to project-based retains saved experiment history under **Previous experiments**. A project-based course cannot change to another type while projects remain linked to it. First edit those projects' **Project for** field to make them personal or link them to another project-based lab. Their milestones, TODOs and completion state stay intact.

Database schema 4 adds the nullable submission deadlines. Migrations preserve old experiment statuses and leave their deadlines unset. The full schema 1 → 2 → 3 → 4 path is tested. Backups keep their existing format version with additive fields; old backups remain importable. Destructive database migration is disabled.
