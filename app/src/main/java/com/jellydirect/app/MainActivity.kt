package com.jellydirect.app

import android.app.UiModeManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jellydirect.app.data.Item
import com.jellydirect.app.data.JellyApi
import com.jellydirect.app.ui.AuthScreen
import com.jellydirect.app.ui.Bg
import com.jellydirect.app.ui.DetailScreen
import com.jellydirect.app.ui.HomeScreen
import com.jellydirect.app.ui.JellyTheme
import com.jellydirect.app.ui.LibrariesScreen
import com.jellydirect.app.ui.LibraryScreen
import com.jellydirect.app.ui.ListScreen
import com.jellydirect.app.ui.LiveTvScreen
import com.jellydirect.app.ui.LocalIsTv
import com.jellydirect.app.ui.SearchScreen
import com.jellydirect.app.ui.SettingsScreen
import com.jellydirect.app.ui.playItem
import com.jellydirect.app.ui.rememberLoad
import com.jellydirect.app.ui.Show

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
                    Box(Modifier.fillMaxSize()) {
                        val a = api
                        if (a == null) AuthScreen(onDone = {}) else Shell(a)
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
private fun Shell(api: JellyApi) {
    val nav = rememberNavController()
    val tv = LocalIsTv.current
    val dests = listOf(HOME, LIBS, LIVE, SEARCH, SETTINGS)
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val showNav = route == null || dests.any { it.route == route }

    fun open(i: Item) = nav.navigate("detail/${Uri.encode(i.id)}")
    fun openLib(i: Item) = nav.navigate("library/${Uri.encode(i.id)}")

    val content: @Composable (Modifier) -> Unit = { mod ->
        NavHost(nav, startDestination = HOME.route, modifier = mod) {
            composable("home") { HomeScreen(api, ::open, ::openLib) }
            composable("libraries") { LibrariesScreen(api, ::openLib) }
            composable("live") { LiveTvScreen(api) }
            composable("search") { SearchScreen(api, ::open) }
            composable("settings") { SettingsScreen(onAddAccount = { nav.navigate("auth?url=${Uri.encode(it ?: "")}") }) }
            composable("library/{id}") { e ->
                val id = e.arguments?.getString("id").orEmpty()
                val l = rememberLoad(api, "item:$id") { api.item(id) }
                l.Show { v ->
                    LibraryScreen(api, v, ::open) { key -> nav.navigate("list/$key") }
                }
            }
            composable("list/{key}") { e -> ListScreen(api, e.arguments?.getString("key").orEmpty(), ::open) }
            composable("detail/{id}") { e ->
                val id = e.arguments?.getString("id").orEmpty()
                DetailScreen(api, id, ::open)
            }
            composable("auth?url={url}") { e ->
                val preset = e.arguments?.getString("url")?.takeIf { it.isNotBlank() }
                AuthScreen(onDone = { nav.popBackStack() }, onCancel = { nav.popBackStack() }, presetUrl = preset)
            }
        }
    }

    if (tv) {
        Row(Modifier.fillMaxSize()) {
            if (showNav) NavigationRail(containerColor = Bg) {
                dests.forEach { d ->
                    NavigationRailItem(selected = route == d.route, onClick = { nav.top(d.route) },
                        icon = { Icon(d.icon, d.label) }, label = { Text(d.label) })
                }
            }
            content(Modifier.weight(1f))
        }
    } else {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                if (showNav) NavigationBar(containerColor = Bg) {
                    dests.forEach { d ->
                        NavigationBarItem(selected = route == d.route, onClick = { nav.top(d.route) },
                            icon = { Icon(d.icon, d.label) }, label = { Text(d.label) })
                    }
                }
            },
        ) { pad -> content(Modifier.padding(pad)) }
    }
}
