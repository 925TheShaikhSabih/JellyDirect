package com.jellydirect.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.jellydirect.app.BuildConfig
import com.jellydirect.app.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The launcher icon, drawn in the app. */
@Composable
fun AppIconMark(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    if (BuildConfig.FIXED_SERVER_URL.isBlank()) {
        Box(modifier.clip(shape).background(Color(0xFF0B0B0D)).border(1.dp, Color(0x22FFFFFF), shape), Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
    } else {
        Box(
            modifier.clip(shape).background(
                Brush.linearGradient(listOf(Crimson, Color(0xFFD5402D), AccentHi), start = Offset(0f, Float.POSITIVE_INFINITY), end = Offset(Float.POSITIVE_INFINITY, 0f)),
            ),
            Alignment.Center,
        ) { Image(painterResource(R.drawable.kumo_cloud), null, Modifier.fillMaxSize()) }
    }
}

/** App icon springs in, holds for a beat while the app gets ready, then pops off the screen. */
@Composable
fun Splash(onFinished: () -> Unit) {
    val scale = remember { Animatable(0.7f) }
    val iconAlpha = remember { Animatable(0f) }
    val veil = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { iconAlpha.animateTo(1f, tween(260)) }
            launch { scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f)) }
        }
        delay(450)
        scale.animateTo(0.92f, tween(110))
        coroutineScope {
            launch { scale.animateTo(2.8f, tween(320, easing = FastOutSlowInEasing)) }
            launch { iconAlpha.animateTo(0f, tween(260)) }
            launch { veil.animateTo(0f, tween(320)) }
        }
        onFinished()
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil.value }.background(Bg), Alignment.Center) {
        AppIconMark(Modifier.size(132.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = iconAlpha.value })
    }
}
