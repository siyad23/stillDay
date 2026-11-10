package dev.stillday.domain

object LabCourses {
    fun validateCourseChange(data: Snapshot, course: Course) {
        require(course.isProjectLab || data.projects.none { it.courseId == course.id }) {
            "This course has lab projects. Relink them as personal projects or to another project-based lab before changing its type."
        }
    }
    /** Changing a lab's structure never deletes projects, milestones, reports or attendance. */
    fun updateCourse(data: Snapshot, course: Course): Snapshot {
        validateCourseChange(data, course)
        val updated = data.copy(courses = data.courses.filterNot { it.id == course.id } + course)
        if (!course.isProjectLab || updated.projects.any { it.courseId == course.id }) return updated
        val semester = updated.semesters.firstOrNull { it.id == course.semesterId } ?: error("Add the semester before its lab course.")
        return updated.copy(projects = updated.projects + Project(title = course.name, dueDate = semester.end, courseId = course.id))
    }
}
