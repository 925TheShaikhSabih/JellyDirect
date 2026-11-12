package com.jellydirect.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.jellydirect.app.data.Account
import kotlin.math.abs

/** "Who is watching?": swipe between the saved profiles, then continue. */
@Composable
fun ProfilePicker(accounts: List<Account>, activeKey: String?, onPick: (Account) -> Unit) {
    val pager = rememberPagerState(initialPage = accounts.indexOfFirst { it.key == activeKey }.coerceAtLeast(0)) { accounts.size }
    fun current() = accounts[pager.currentPage.coerceIn(0, accounts.lastIndex)]
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(56.dp))
        Row {
            Text("Who is ", style = Type.titleLarge)
            Text("watching", style = Type.titleLarge, color = Accent)
            Text("?", style = Type.titleLarge)
        }
        Spacer(Modifier.weight(1f))
        HorizontalPager(pager, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 110.dp), pageSpacing = 4.dp) { page ->
            val a = accounts[page]
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer {
                    val off = abs(page - pager.currentPage - pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    val s = 1f - 0.4f * off
                    scaleX = s; scaleY = s; alpha = 1f - 0.6f * off
                },
                contentAlignment = Alignment.Center,
            ) { Avatar(userImage(a.serverUrl, a.userId), a.userName, Modifier.fillMaxSize(), letterSp = 72) }
        }
        Crossfade(current(), label = "who") { a ->
            Column(Modifier.padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(a.userName, style = Type.titleLarge)
                Text(a.serverName, style = Type.bodySmall, color = InkDim)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = { onPick(current()) }, modifier = Modifier.padding(bottom = 48.dp)) { Text("Continue") }
    }
}
