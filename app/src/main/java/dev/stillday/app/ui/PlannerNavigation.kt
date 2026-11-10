package dev.stillday.app.ui

import kotlinx.serialization.Serializable

/** Each main tab owns its page history; opening a detail never replaces its parent. */
@Serializable
data class PlannerNavigation(
    val selectedTab: String = "Today",
    val histories: Map<String, List<String>> = tabs.associateWith { listOf(it) }
) {
    val stack: List<String> get() = histories.getValue(selectedTab)
    val route: String get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1 || selectedTab != "Today"

    fun navigate(destination: String): PlannerNavigation = if (destination == route) this
        else copy(histories = histories + (selectedTab to (stack + destination)))

    fun selectTab(tab: String): PlannerNavigation {
        require(tab in tabs)
        return copy(selectedTab = tab, histories = if (tab == selectedTab) histories + (tab to listOf(tab)) else histories)
    }

    fun back(): PlannerNavigation = when {
        stack.size > 1 -> copy(histories = histories + (selectedTab to stack.dropLast(1)))
        selectedTab != "Today" -> copy(selectedTab = "Today", histories = histories + ("Today" to listOf("Today")))
        else -> this
    }

    companion object {
        val tabs = listOf("Today", "Projects", "University", "Life")
        fun start(destination: String?): PlannerNavigation {
            val root = when {
                destination in tabs -> destination!!
                destination?.startsWith("project:") == true -> "Projects"
                else -> "Today"
            }
            val initial = PlannerNavigation(selectedTab = root)
            return if (destination.isNullOrEmpty() || destination == root) initial else initial.navigate(destination)
        }
    }
}
