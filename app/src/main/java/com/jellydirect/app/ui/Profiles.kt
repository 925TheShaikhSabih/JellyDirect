package com.jellydirect.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.jellydirect.app.data.Account
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * "Who is watching?" — shown on every launch, even with a single profile (like a console's user select).
 * Swipe or use the D-pad to move between profiles; the last card adds a new one.
 */
@Composable
fun ProfilePicker(accounts: List<Account>, activeKey: String?, onAdd: () -> Unit, onPick: (Account) -> Unit) {
    val count = accounts.size + 1
    val pager = rememberPagerState(initialPage = accounts.indexOfFirst { it.key == activeKey }.coerceAtLeast(0)) { count }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val page = pager.currentPage.coerceIn(0, count - 1)
    val account = accounts.getOrNull(page)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun go(delta: Int) = scope.launch { pager.animateScrollToPage((pager.currentPage + delta).coerceIn(0, count - 1)) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) false
            else when (e.key) {
                Key.DirectionLeft -> { go(-1); true }
                Key.DirectionRight -> { go(1); true }
                else -> false
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Row {
            Text("Who is ", style = Type.titleLarge)
            Text("watching", style = Type.titleLarge, color = Accent)
            Text("?", style = Type.titleLarge)
        }
        Spacer(Modifier.weight(1f))
        HorizontalPager(pager, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 110.dp), pageSpacing = 4.dp) { p ->
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer {
                    val off = abs(p - pager.currentPage - pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    val s = 1f - 0.4f * off
                    scaleX = s; scaleY = s; alpha = 1f - 0.6f * off
                },
                contentAlignment = Alignment.Center,
            ) {
                val a = accounts.getOrNull(p)
                if (a != null) Avatar(userImage(a.serverUrl, a.userId), a.userName, Modifier.fillMaxSize(), letterSp = 72)
                else Box(Modifier.fillMaxSize().glassFill(CircleShape).rim(CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, "Add profile", Modifier.size(72.dp), tint = InkDim)
                }
            }
        }
        Crossfade(page, label = "who") { i ->
            val a = accounts.getOrNull(i)
            Column(Modifier.padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(a?.userName ?: "Add profile", style = Type.titleLarge)
                Text(a?.serverName ?: "Sign in with another account", style = Type.bodySmall, color = InkDim)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { if (account != null) onPick(account) else onAdd() },
            modifier = Modifier.padding(bottom = 48.dp).focusRequester(focus),
        ) { Text(if (account != null) "Continue" else "Add profile") }
    }
}
