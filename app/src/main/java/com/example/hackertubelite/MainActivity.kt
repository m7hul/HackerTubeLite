package com.example.hackertubelite

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

enum class NavTab(val label: String) { Home("Home"), Browse("Browse"), Library("Library"), Settings("Settings") }

sealed interface Screen {
    data object Search : Screen
    data class Category(val cat: Cat) : Screen
    data class Detail(val id: String) : Screen
}

class MainActivity : ComponentActivity(), FullscreenController {

    private val vm: AppViewModel by viewModels()
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var inFullscreen by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = when (vm.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose { }
            }
            HackerTubeTheme(dark) {
                CompositionLocalProvider(LocalFullscreen provides this@MainActivity) {
                    Root(vm)
                    BackHandler(enabled = inFullscreen) { exit() }
                }
            }
        }
    }

    override fun enter(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        view.setBackgroundColor(Color.BLACK)
        (window.decorView as FrameLayout).addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        inFullscreen = true
    }

    override fun exit() {
        val view = customView ?: return
        val callback = customCallback
        customView = null
        customCallback = null
        (window.decorView as FrameLayout).removeView(view)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        inFullscreen = false
        callback?.onCustomViewHidden()
    }
}

@Composable
fun Root(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(NavTab.Home) }
    var stack by remember { mutableStateOf(listOf<Screen>()) }

    fun push(s: Screen) { stack = stack + s }
    fun pop() { stack = stack.dropLast(1) }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    BackHandler(enabled = stack.isEmpty() && tab != NavTab.Home) { tab = NavTab.Home }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (stack.isEmpty()) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    when (t) {
                                        NavTab.Home -> Icons.Filled.Home
                                        NavTab.Browse -> Icons.Filled.ListIcon
                                        NavTab.Library -> Icons.Filled.Favorite
                                        NavTab.Settings -> Icons.Filled.Settings
                                    },
                                    contentDescription = t.label,
                                )
                            },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                vm.loading -> LoadingScreen()
                vm.failed -> EmptyState("Couldn’t load the catalog.", Modifier.align(Alignment.Center))
                else -> {
                    val top = stack.lastOrNull()
                    if (top != null) {
                        key(top) {
                            when (top) {
                                is Screen.Search -> SearchScreen(vm, ::pop) { push(Screen.Detail(it)) }
                                is Screen.Category -> CategoryScreen(vm, top.cat, ::pop) { push(Screen.Detail(it)) }
                                is Screen.Detail -> DetailScreen(
                                    vm, top.id, ::pop,
                                    onOpen = { push(Screen.Detail(it)) },
                                    onCategory = { push(Screen.Category(it)) },
                                )
                            }
                        }
                    } else {
                        when (tab) {
                            NavTab.Home -> HomeScreen(vm, { push(Screen.Detail(it)) }, { push(Screen.Search) })
                            NavTab.Browse -> BrowseScreen(vm, { push(Screen.Category(it)) }, { push(Screen.Search) })
                            NavTab.Library -> LibraryScreen(vm) { push(Screen.Detail(it)) }
                            NavTab.Settings -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
