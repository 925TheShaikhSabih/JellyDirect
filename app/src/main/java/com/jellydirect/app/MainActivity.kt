package com.jellydirect.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.runtime.saveable.rememberSaveable
import com.jellydirect.app.ui.MemCache
import com.jellydirect.app.ui.ProfilePicker

import android.app.UiModeManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jellydirect.app.data.Item
import com.jellydirect.app.data.JellyApi
import com.jellydirect.app.ui.Accent
import com.jellydirect.app.ui.AuthScreen
import com.jellydirect.app.ui.Bg
import com.jellydirect.app.ui.CrashDialog
import com.jellydirect.app.ui.DetailScreen
import com.jellydirect.app.ui.HomeScreen
import com.jellydirect.app.ui.Icon
import com.jellydirect.app.ui.InkDim
import com.jellydirect.app.ui.JellyTheme
import com.jellydirect.app.ui.LibrariesScreen
import com.jellydirect.app.ui.LibraryScreen
import com.jellydirect.app.ui.ListScreen
import com.jellydirect.app.ui.LiveTvScreen
import com.jellydirect.app.ui.LocalIsTv
import com.jellydirect.app.ui.SearchScreen
import com.jellydirect.app.ui.SettingsScreen
import com.jellydirect.app.ui.Show
import com.jellydirect.app.ui.Text
import com.jellydirect.app.ui.Type
import com.jellydirect.app.ui.ambient
import com.jellydirect.app.ui.focusClick
import com.jellydirect.app.ui.glassFill
import com.jellydirect.app.ui.rememberLoad
import com.jellydirect.app.ui.rim
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val tv = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature("android.software.leanback")
        val app = application as App
        setContent {
            JellyTheme {
                CompositionLocalProvider(LocalIsTv provides tv) {
                    val api by app.api.collectAsState()
                    var crash by remember { mutableStateOf(app.store.takeLastCrash()) }
                    crash?.let { CrashDialog(it) { crash = null } }
                    Box(Modifier.fillMaxSize().ambient()) {
                        val a = api
                        val accounts by app.store.accounts.collectAsState()
                        var chosen by rememberSaveable { mutableStateOf(false) }
                        if (a == null) AuthScreen(onDone = {})
                        else if (!chosen && accounts.size > 1) ProfilePicker(accounts, app.store.activeKey) { acc ->
                            if (acc.key != app.store.activeKey) { MemCache.map.clear(); app.switchTo(acc) }
                            chosen = true
                        } else Shell(a)
                    }
                }
            }
        }
    }
}

private class Dest(val route: String, val label: String, val icon: ImageVector)

private val HOME = Dest("home", "Home", Icons.Filled.Home)
private val LIBS = Dest("libraries", "Libraries", Icons.Filled.VideoLibrary)
private val LIVE = Dest("live", "Live TV", Icons.Filled.LiveTv)
private val SEARCH = Dest("search", "Search", Icons.Filled.Search)
private val SETTINGS = Dest("settings", "Settings", Icons.Filled.Settings)

private fun NavHostController.top(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun NavItem(d: Dest, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Accent else InkDim
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.focusClick(shape, onClick = onClick)
            .then(if (selected) Modifier.glassFill(shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(d.icon, d.label, tint = tint)
        Text(d.label, style = Type.labelSmall, color = tint)
    }
}

@Composable
private fun PillItem(d: Dest, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val tint by animateColorAsState(if (selected) Accent else InkDim, tween(220), label = "navTint")
    val edge by animateColorAsState(if (selected) Accent else Color.Transparent, tween(220), label = "navEdge")
    val fill by animateColorAsState(if (selected) Color(0x1FE5382D) else Color.Transparent, tween(220), label = "navFill")
    Row(
        Modifier.animateContentSize(spring(dampingRatio = 0.75f, stiffness = 420f))
            .focusClick(shape, onClick = onClick)
            .background(fill, shape)
            .border(1.5.dp, edge, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(d.icon, d.label, tint = tint)
        if (selected) Text(d.label, Modifier.padding(start = 6.dp), style = Type.labelLarge, color = tint, maxLines = 1)
    }
}

/** Floating pill that blurs what scrolls underneath it. The selected tab grows to show its name. */
@Composable
private fun GlassBar(dests: List<Dest>, route: String?, haze: HazeState, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.navigationBarsPadding().padding(bottom = 14.dp)
            .clip(shape)
            .hazeEffect(state = haze, style = HazeStyle(backgroundColor = Bg, tints = emptyList(), blurRadius = 24.dp, noiseFactor = 0.03f))
            .background(Color(0xB8141416))
            .rim(shape)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) { dests.forEach { d -> PillItem(d, route == d.route) { onSelect(d.route) } } }
}

@Composable
private fun GlassRail(dests: List<Dest>, route: String?, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.statusBarsPadding().padding(12.dp).fillMaxHeight().clip(shape).glassFill(shape).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) { dests.forEach { d -> NavItem(d, route == d.route) { onSelect(d.route) } } }
}

@Composable
private fun Shell(api: JellyApi) {
    val nav = rememberNavController()
    val tv = LocalIsTv.current
    val haze = rememberHazeState()
    val dests = listOf(HOME, LIBS, LIVE, SEARCH, SETTINGS)
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val showNav = route == null || dests.any { it.route == route }

    fun open(i: Item) = nav.navigate("detail/${Uri.encode(i.id)}")
    fun openLib(i: Item) = nav.navigate("library/${Uri.encode(i.id)}")

    Row(Modifier.fillMaxSize()) {
        if (tv && showNav) GlassRail(dests, route) { nav.top(it) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            NavHost(
                nav, startDestination = HOME.route, modifier = Modifier.fillMaxSize().hazeSource(haze),
                enterTransition = { fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.97f) },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = { fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.97f) },
            ) {
                composable("home") { HomeScreen(api, ::open, ::openLib, onSearch = { nav.top("search") }, onProfile = { nav.top("settings") }) }
                composable("libraries") { LibrariesScreen(api, ::openLib) }
                composable("live") { LiveTvScreen(api) }
                composable("search") { SearchScreen(api, ::open) }
                composable("settings") { SettingsScreen(onAddAccount = { nav.navigate("auth?url=${Uri.encode(it ?: "")}") }) }
                composable("library/{id}") { e ->
                    val id = e.arguments?.getString("id").orEmpty()
                    val l = rememberLoad(api, "item:$id") { api.item(id) }
                    l.Show { v -> LibraryScreen(api, v, ::open) { key -> nav.navigate("list/$key") } }
                }
                composable("list/{key}") { e -> ListScreen(api, e.arguments?.getString("key").orEmpty(), ::open) }
                composable("detail/{id}") { e -> DetailScreen(api, e.arguments?.getString("id").orEmpty(), ::open) }
                composable("auth?url={url}") { e ->
                    val preset = e.arguments?.getString("url")?.takeIf { it.isNotBlank() }
                    AuthScreen(onDone = { nav.popBackStack() }, onCancel = { nav.popBackStack() }, presetUrl = preset)
                }
            }
            if (!tv) AnimatedVisibility(
                showNav, Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)),
                exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            ) { GlassBar(dests, route, haze) { nav.top(it) } }
        }
    }
}
