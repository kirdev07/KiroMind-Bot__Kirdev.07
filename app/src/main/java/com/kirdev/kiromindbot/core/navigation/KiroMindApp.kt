package com.kirdev.kiromindbot.core.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kirdev.kiromindbot.core.designsystem.AppBlack
import com.kirdev.kiromindbot.core.designsystem.AppMuted
import com.kirdev.kiromindbot.core.designsystem.AppSurface
import com.kirdev.kiromindbot.core.di.AppGraph
import com.kirdev.kiromindbot.feature.protection.ui.BlacklistScreen
import com.kirdev.kiromindbot.core.service.BotService
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.kirdev.kiromindbot.feature.answers.ui.AnswerEditorScreen
import com.kirdev.kiromindbot.feature.answers.ui.AnswersScreen
import com.kirdev.kiromindbot.feature.learning.ui.UnknownQuestionsScreen
import android.net.Uri
import com.kirdev.kiromindbot.feature.engine.ui.EngineScreen
import com.kirdev.kiromindbot.feature.engine.ui.TestMindCoreScreen
import com.kirdev.kiromindbot.feature.home.ui.HomeScreen
import com.kirdev.kiromindbot.feature.settings.ui.SettingsDetailScreen
import com.kirdev.kiromindbot.feature.settings.ui.SettingsScreen
import com.kirdev.kiromindbot.feature.terminal.ui.TerminalScreen

private data class BottomTab(val id: String, val label: String, val icon: ImageVector)
private val bottomTabs = listOf(
    BottomTab("home", "Главная", Icons.Outlined.Home),
    BottomTab("terminal", "Терминал", Icons.Outlined.Terminal),
    BottomTab("answers", "База", Icons.Outlined.Storage),
    BottomTab("engine", "ИИ", Icons.Outlined.Psychology),
    BottomTab("settings", "Настройки", Icons.Outlined.Settings)
)

@Composable
fun KiroMindApp() {
    val nav = rememberNavController()
    val graph = AppGraph.get(LocalContext.current)
    val repository = graph.answers
    val session = graph.session
    val unknownQuestions = graph.unknownQuestions
    val pipeline = graph.testPipeline
    val preferences by graph.preferences.collectAsState()
    val telegramStatus by graph.telegram.status.collectAsState()
    val hasToken by graph.telegram.hasToken.collectAsState()
    val blocked by graph.blacklist.items.collectAsState()
    val aiKeys by graph.ai.keys.collectAsState()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun toggleBot() {
        val on = !session.running
        if (on && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        graph.setBotRunning(on)
    }
    LaunchedEffect(Unit) {
        graph.restoreBot("восстановлен после перезапуска")
        graph.checkUpdatesIfDue()
    }
    val answers by repository.entries.collectAsState()
    val unknownList by unknownQuestions.items.collectAsState()
    val backEntry by nav.currentBackStackEntryAsState()
    val current = backEntry?.destination?.route.orEmpty().substringBefore("/")

    fun goTab(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = AppBlack,
        bottomBar = {
            NavigationBar(containerColor = AppSurface, tonalElevation = androidx.compose.ui.unit.Dp.Unspecified) {
                bottomTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.id,
                        onClick = { goTab(tab.id) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = {
                            // Shrinks instead of clipping when the system font scale is large.
                            val tint = LocalContentColor.current
                            BasicText(
                                tab.label,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium,
                                color = { tint },
                                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
                            )
                        },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = AppMuted,
                            unselectedTextColor = AppMuted
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.fillMaxSize().background(AppBlack).padding(padding)
        ) {
            composable("home") {
                HomeScreen(session, answers.size, telegramStatus, hasToken, onToggle = ::toggleBot, onTelegram = { nav.navigate("settings/telegram") }, onTerminal = { goTab("terminal") }, onAnswers = { goTab("answers") }, onEngine = { goTab("engine") }, onSecurity = { nav.navigate("settings/security") })
            }
            composable("terminal") { TerminalScreen(session) }
            composable("answers") {
                AnswersScreen(answers = answers, unknownCount = unknownList.size, onEdit = { id -> nav.navigate("answers/edit/$id") }, onAdd = { nav.navigate("answers/edit/new") }, onFavorite = repository::toggleFavorite, onUnknown = { nav.navigate("answers/unknown") })
            }
            composable("answers/unknown") {
                UnknownQuestionsScreen(
                    questions = unknownList,
                    onAnswer = { q -> nav.navigate("answers/learn/${Uri.encode(q.key)}") },
                    onDismiss = { q -> unknownQuestions.dismiss(q.key) },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("answers/learn/{key}") { back ->
                val key = back.arguments?.getString("key").orEmpty()
                AnswerEditorScreen(
                    existing = null,
                    nextId = (answers.maxOfOrNull { it.id } ?: 0L) + 1L,
                    allAnswers = answers,
                    presetQuestion = unknownList.firstOrNull { it.key == key }?.sample ?: key,
                    // Saved by the owner explicitly, then removed from the queue.
                    onSave = { entry -> repository.upsert(entry); unknownQuestions.dismiss(key); nav.popBackStack() },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("answers/edit/{id}") { back ->
                val id = back.arguments?.getString("id")?.toLongOrNull()
                AnswerEditorScreen(
                    existing = id?.let(repository::find),
                    nextId = (answers.maxOfOrNull { it.id } ?: 0L) + 1L,
                    allAnswers = answers,
                    onSave = { entry -> repository.upsert(entry); nav.popBackStack() },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("engine") {
                EngineScreen(answers.size, preferences.contextEnabled,
                    aiStatus = com.kirdev.kiromindbot.feature.ai.data.AiKind.of(preferences.aiProvider).let { k ->
                        k.title + " · " + when { k !in aiKeys -> "ключ не задан"; preferences.externalAiEnabled -> "включён"; else -> "выключен" }
                    },
                    onContextChanged = { graph.updatePreferences(preferences.copy(contextEnabled = it)) }, onTest = { nav.navigate("engine/test") }, onProvider = { nav.navigate("engine/provider") })
            }
            composable("engine/test") {
                TestMindCoreScreen(
                    pipeline = pipeline,
                    settings = graph.behavior(),
                    unknownCount = { unknownQuestions.all().size },
                    persona = preferences.persona,
                    onPersona = { graph.updatePreferences(preferences.copy(persona = it)) },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("engine/provider") {
                com.kirdev.kiromindbot.feature.engine.ui.ProviderScreen(
                    keys = aiKeys,
                    settings = preferences,
                    usedToday = graph.aiUsedToday(),
                    onChange = graph::updatePreferences,
                    onSaveKey = graph::saveAiKey,
                    onDeleteKey = graph::deleteAiKey,
                    onListModels = graph::listAiModels,
                    onBack = { nav.popBackStack() }
                )
            }
            composable("settings") { SettingsScreen(onSelect = { nav.navigate("settings/$it") }) }
            composable("settings/updates") {
                com.kirdev.kiromindbot.feature.update.ui.UpdateScreen(graph.updates, onBack = { nav.popBackStack() })
            }
            composable("settings/blacklist") {
                BlacklistScreen(
                    blocked = blocked,
                    owners = graph.behavior().ownerIds,
                    onBlock = { id, reason -> graph.blacklist.block(id, reason, System.currentTimeMillis()) },
                    onUnblock = { graph.blacklist.unblock(it) },
                    onBack = { nav.popBackStack() }
                )
            }
            composable("settings/{section}") { back ->
                SettingsDetailScreen(
                    section = back.arguments?.getString("section").orEmpty(),
                    settings = preferences,
                    onChange = graph::updatePreferences,
                    telegram = graph.telegram,
                    telegramStatus = telegramStatus,
                    hasToken = hasToken,
                    blockedCount = blocked.count { it.isActive(System.currentTimeMillis()) },
                    onBlacklist = { nav.navigate("settings/blacklist") },
                    onUpdates = { nav.navigate("settings/updates") },
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
}
