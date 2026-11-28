package com.muzhou.droidagent.ui

import com.muzhou.droidagent.data.AppContainer
import com.muzhou.droidagent.data.ThemeMode
import com.muzhou.droidagent.ui.chat.ChatScreen
import com.muzhou.droidagent.ui.chat.ChatViewModel
import com.muzhou.droidagent.ui.drawer.SessionDrawer
import com.muzhou.droidagent.ui.files.FileEditorScreen
import com.muzhou.droidagent.ui.files.FilesScreen
import com.muzhou.droidagent.ui.settings.SettingsScreen
import com.muzhou.droidagent.ui.settings.SettingsViewModel
import com.muzhou.droidagent.ui.terminal.TerminalScreen
import com.muzhou.droidagent.ui.theme.DroidAgentTheme
import com.muzhou.droidagent.ui.workspace.WorkspaceSheet
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch

@Composable
fun DroidAgentRoot(container: AppContainer) {
    val chatViewModel: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by chatViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    DroidAgentTheme(mode = state.settings.theme) {
        val navController = rememberNavController()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var showWorkspaceSheet by remember { mutableStateOf(false) }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val route = backStackEntry?.destination?.route

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = route == "chat",
            drawerContent = {
                SessionDrawer(
                    state = state,
                    onClose = { scope.launch { drawerState.close() } },
                    onNewSession = { chatViewModel.newSession() },
                    onNewSessionInWorkspace = { chatViewModel.newSession(it) },
                    onSelectSession = { chatViewModel.selectSession(it) },
                    onRenameSession = { id, title -> chatViewModel.renameSession(id, title) },
                    onDeleteSession = { chatViewModel.deleteSession(it) },
                    onManageWorkspaces = { showWorkspaceSheet = true },
                    onSearchQuery = { chatViewModel.searchSessions(it) },
                    onOpenSettings = { navController.navigate("settings") },
                    onToggleTheme = {
                        val next = when (state.settings.theme) {
                            ThemeMode.LIGHT -> ThemeMode.DARK
                            ThemeMode.DARK -> ThemeMode.LIGHT
                            ThemeMode.SYSTEM -> if (isSystemDark(context)) ThemeMode.LIGHT else ThemeMode.DARK
                        }
                        settingsViewModel.saveSettings(state.settings.copy(theme = next))
                    },
                )
            },
        ) {
            NavHost(
                navController = navController,
                startDestination = "chat",
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                    ) + fadeIn(tween(180))
                },
                exitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Left,
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                    ) + fadeOut(tween(160))
                },
                popEnterTransition = {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                    ) + fadeIn(tween(180))
                },
                popExitTransition = {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Right,
                        animationSpec = tween(280, easing = FastOutSlowInEasing),
                    ) + fadeOut(tween(160))
                },
            ) {
                composable("chat") {
                    ChatScreen(
                        state = state,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNewSession = { chatViewModel.newSession() },
                        onOpenFiles = { navController.navigate("files") },
                        onOpenTerminal = { navController.navigate("terminal") },
                        onSend = { text, attachments -> chatViewModel.send(text, attachments) },
                        onStop = { chatViewModel.stop() },
                        onApprove = { decision -> chatViewModel.resolveApproval(decision) },
                        onRegenerate = { chatViewModel.regenerate() },
                        onReasoningEffortChange = { chatViewModel.setReasoningEffort(it) },
                        onAgentModeChange = { chatViewModel.setAgentMode(it) },
                        onOpenModelSettings = { navController.navigate("settings?page=model") },
                    )
                }
                composable(
                    route = "settings?page={page}",
                    arguments = listOf(
                        navArgument("page") {
                            type = NavType.StringType
                            defaultValue = "home"
                        },
                    ),
                ) { entry ->
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        initialPage = entry.arguments?.getString("page") ?: "home",
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("files") {
                    val workspace = state.workspace
                    if (workspace == null) {
                        LoadingPane("正在加载工作区…")
                    } else {
                        FilesScreen(
                            workspace = workspace,
                            onBack = { navController.popBackStack() },
                            onOpenFile = { path ->
                                navController.navigate("editor?path=${Uri.encode(path)}")
                            },
                        )
                    }
                }
                composable(
                    route = "editor?path={path}",
                    arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
                ) { entry ->
                    val workspace = state.workspace
                    val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
                    if (workspace == null || path.isBlank()) {
                        LoadingPane("无法打开文件")
                    } else {
                        FileEditorScreen(
                            workspace = workspace,
                            path = path,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable("terminal") {
                    val workspace = state.workspace
                    if (workspace == null) {
                        LoadingPane("正在加载工作区…")
                    } else {
                        TerminalScreen(
                            workspace = workspace,
                            termuxEnabled = state.settings.termuxEnabled,
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }

        if (showWorkspaceSheet) {
            WorkspaceSheet(
                state = state,
                onDismiss = { showWorkspaceSheet = false },
                onCreateLocal = { chatViewModel.createLocalWorkspace(it) },
                onCreateExternal = { chatViewModel.createExternalWorkspace(it) },
                onAddSaf = { chatViewModel.addSafWorkspace(it) },
                onDelete = { chatViewModel.deleteWorkspace(it) },
            )
        }
    }
}

@Composable
private fun LoadingPane(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
            Text(text)
        }
    }
}

private fun isSystemDark(context: android.content.Context): Boolean {
    val mode = context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK
    return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
}
