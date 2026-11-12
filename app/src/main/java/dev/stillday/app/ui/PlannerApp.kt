package dev.stillday.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stillday.app.PlannerViewModel
import dev.stillday.domain.*
import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private data class AssessmentRequest(val courseId: String? = null, val assessment: Assessment? = null, val periodId: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlannerApp(vm: PlannerViewModel, openUpdates: Boolean = false, initialRoute: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val destination = initialRoute?.takeIf { it in listOf("Today", "Projects", "Calendar", "University", "Life", "Planning", "Settings") ||
        it.startsWith("class:") || it.startsWith("assessment:") || it.startsWith("assignment:") || it.startsWith("project:") || it.startsWith("exam-period:") || it.startsWith("work-slot:") || it.startsWith("experiment:") }
    var navigation by rememberSaveable(stateSaver = Saver<PlannerNavigation, String>(
        save = { Json.encodeToString(it) }, restore = { runCatching { Json.decodeFromString<PlannerNavigation>(it) }.getOrNull() })) {
        mutableStateOf(PlannerNavigation.start(if (openUpdates) "Settings" else destination))
    }
    val route = navigation.route
    val pageStates = rememberSaveableStateHolder()
    val navigate: (String) -> Unit = { navigation = navigation.navigate(it) }
    var form by remember { mutableStateOf<FormSpec?>(null) }
    var assessment by remember { mutableStateOf<AssessmentRequest?>(null) }
    var study by remember { mutableStateOf<StudyItem?>(null) }
    var chapterStudy by remember { mutableStateOf<Chapter?>(null) }
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        vm.preferences { it.copy(notifications = allowed, reminderSetupDone = true) }
    }
    LaunchedEffect(vm) { vm.events.collect { snack.showSnackbar(it) } }
    LaunchedEffect(state.ready, state.preferences.onboardingComplete) {
        if (state.ready && !state.preferences.onboardingComplete) navigation = PlannerNavigation()
    }
    BackHandler(navigation.canGoBack) { navigation = navigation.back() }
    StilldayTheme(state.preferences.theme) {
        if (state.ready) DriveBackupLifecycle(vm)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!state.ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            } else if (!state.preferences.onboardingComplete) {
                Onboarding(vm)
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snack) },
                    topBar = {
                        TopAppBar(title = { Text(if (route.startsWith("work-slot:")) "Time slot" else if (route.startsWith("exam-period:")) "Exam routine" else if (route.contains(':')) route.substringBefore(':').pretty() else if (route == "Today") "stillday" else route,
                            style = MaterialTheme.typography.titleLarge) },
                            navigationIcon = { if (navigation.stack.size > 1) IconButton(onClick = { navigation = navigation.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                            actions = {
                                if (route == "Today") IconButton(onClick = { navigate("Calendar") }) { Icon(Icons.Outlined.CalendarMonth, "Open calendar") }
                                if (route in listOf("Today", "Calendar")) IconButton(onClick = { assessment = AssessmentRequest() }) { Icon(Icons.Outlined.Add, "Quick add quiz") }
                                IconButton(onClick = { navigate("Settings") }) { Icon(Icons.Outlined.Tune, "Settings") }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
                    },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                            listOf("Today" to Icons.Outlined.WbSunny, "Projects" to Icons.Outlined.FolderOpen,
                                "University" to Icons.Outlined.School, "Life" to Icons.Outlined.Spa).forEach { (label, icon) ->
                                NavigationBarItem(selected = navigation.selectedTab == label, onClick = { navigation = navigation.selectTab(label) }, icon = { Icon(icon, null) }, label = { Text(label) })
                            }
                        }
                    },
                    floatingActionButton = {}
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        val open: (FormSpec) -> Unit = { form = it }
                        pageStates.SaveableStateProvider("${navigation.selectedTab}:$route") { when {
                            route == "Today" -> TodayScreen(vm, state, open, navigate, { study = it })
                            route == "Calendar" -> CalendarScreen(vm, state, open, navigate)
                            route == "University" -> UniversityScreen(vm, state, open, navigate)
                            route == "Life" -> LifeScreen(vm, state, open, navigate)
                            route == "Planning" -> Page { DayPlanningControls(vm, state, open) }
                            route == "Projects" -> ProjectsScreen(vm, state, open, navigate)
                            route == "Settings" -> SettingsScreen(vm, state, open, navigate = navigate, onEnableNotifications = {
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else vm.preferences { it.copy(notifications = true) }
                            })
                            route == "Notifications" -> NotificationSettings(vm, state, onEnable = {
                                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else vm.preferences { it.copy(notifications = true) }
                            })
                            route.startsWith("work-slot:") -> WorkSlotScreen(vm, state, route.substringAfter(':'), navigate)
                            route.startsWith("semester:") -> SemesterScreen(vm, state, route.substringAfter(':'), open, navigate)
                            route.startsWith("exam-period:") -> ExamRoutineScreen(vm, state, route.substringAfter(':'), open,
                                onAssessment = { id, old -> assessment = AssessmentRequest(id, old, route.substringAfter(':')) })
                            route.startsWith("course:") -> CourseScreen(vm, state, route.substringAfter(':'), open, navigate,
                                onAssessment = { id, old -> assessment = AssessmentRequest(id, old) })
                            route.startsWith("chapter:") -> ChapterScreen(vm, state, route.substringAfter(':'), open, { chapterStudy = it })
                            route.startsWith("assessment:") -> AssessmentScreen(vm, state, route.substringAfter(':'), { assessment = AssessmentRequest(it.courseId, it) })
                            route.startsWith("experiment:") -> ExperimentScreen(vm, state, route.substringAfter(':'), open, navigate)
                            route.startsWith("class:") -> OccurrenceScreen(vm, state, route.substringAfter(':'), open, navigate)
                            route.startsWith("assignment:") -> WorkScreen(vm, state, route.substringAfter(':'), false, open, navigate)
                            route.startsWith("project:") -> WorkScreen(vm, state, route.substringAfter(':'), true, open, navigate)
                        } }
                    }
                }
            }
            form?.let { FormEditor(it) { form = null } }
            assessment?.let { request -> AssessmentEditor(vm, request.courseId, request.assessment, request.periodId) { assessment = null } }
            study?.let { item -> StudyDialog(vm, item) { study = null } }
            chapterStudy?.let { ChapterStudyDialog(vm, state, it) { chapterStudy = null } }
            if (state.ready && state.preferences.onboardingComplete) UpdateHost(vm.updates, automatic = !state.preferences.demoData, openUpdates = openUpdates)
            if (state.ready && state.preferences.onboardingComplete && !state.preferences.demoData && !state.preferences.reminderSetupDone) {
                AlertDialog(onDismissRequest = { vm.preferences { it.copy(reminderSetupDone = true) } }, title = { Text("A quiet daily briefing") },
                    text = { Text("Get one morning plan, plus reminders before classes, assessments and deadlines. Change the timing in Settings.") },
                    confirmButton = { TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.preferences { it.copy(notifications = true, reminderSetupDone = true) }
                    }) { Text("Enable reminders") } },
                    dismissButton = { TextButton(onClick = { vm.preferences { it.copy(reminderSetupDone = true) } }) { Text("Later") } })
            }
        }
    }
}

@Composable private fun Onboarding(vm: PlannerViewModel) {
    var form by remember { mutableStateOf<FormSpec?>(null) }
    var localSetup by rememberSaveable { mutableStateOf(false) }
    val drive by vm.driveBackup.state.collectAsStateWithLifecycle()
    Page {
        Spacer(Modifier.height(24.dp))
        Text("stillday", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("A little less\nto decide.", style = MaterialTheme.typography.displaySmall)
        Text("Your classes, study plan, meals and everyday tasks. One quiet place to know what comes next.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        DriveBackupControls(vm, setup = true)
        if (localSetup || drive.link != null) QuietCard {
            Text("Start with your semester", style = MaterialTheme.typography.titleLarge)
            Text(if (drive.link != null) "Your semester and future changes will be backed up automatically." else "Your data stays on this device until you connect Google Drive or export a backup.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { form = FormSpec("Add your current semester", listOf(
                FormField("title", "Semester name", helper = "For example, Semester 7 or Year 3 — Semester 1."),
                FormField("start", "Start date", vm.repository.today().toString()),
                FormField("end", "Expected end date")),
                "Start with whichever semester you are in. Only this semester is created; add later semesters when their dates are known.") { v ->
                val title = v.required("title"); val start = v.dateValue("start").date(); val end = v.dateValue("end").date()
                require(!end.isBefore(start)) { "Expected end date must follow the start date." }
                vm.action { vm.repository.setup(title, start, end) }
            } }, modifier = Modifier.fillMaxWidth()) { Text("Add my semester") }
        }
        if (!localSetup && drive.link == null) TextButton(onClick = { localSetup = true }) { Text("Continue on this device") }
        OutlinedButton(onClick = { vm.action { vm.driveBackup.disconnect(); vm.repository.loadDemo() } }, modifier = Modifier.fillMaxWidth()) { Text("Explore a sample semester") }
        Text("2h study on regular days · 6h during exam focus\nBoth are adjustable in Settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    form?.let { FormEditor(it) { form = null } }
}
