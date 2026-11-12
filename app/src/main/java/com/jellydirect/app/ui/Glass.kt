package com.jellydirect.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// Own minimal component set with a "liquid glass" look. Names match the Material ones the screens already
// use, so the rest of the UI did not need to change when Material was removed.

val Ink = Color(0xFFF4EFEA)
val InkDim = Color(0xFFB0A8AE)
val Danger = Color(0xFFFF6B6B)
private val OnAccent = Color(0xFF1A0A04)
private val AccentHi = Color(0xFFFF9150)

object Type {
    val titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val bodyMedium = TextStyle(fontSize = 14.sp)
    val bodySmall = TextStyle(fontSize = 12.sp)
    val labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

val LocalContentColor = compositionLocalOf { Ink }
val LocalTextStyle = compositionLocalOf { Type.bodyMedium }

@Composable
fun JellyTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Ink, LocalTextStyle provides Type.bodyMedium, content = content)
}

// ---------------------------------------------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------------------------------------------

/** Light catching the edge of a glass pane. */
fun Modifier.rim(shape: Shape, width: Dp = 1.dp): Modifier =
    border(width, Brush.linearGradient(listOf(Color(0x80FFFFFF), Color(0x0DFFFFFF), Color(0x40FFFFFF))), shape)

fun Modifier.glassFill(shape: Shape): Modifier =
    background(Brush.verticalGradient(listOf(Color(0x2AFFFFFF), Color(0x0FFFFFFF))), shape).rim(shape)

/** Soft ember and teal glow behind the whole app. */
fun Modifier.ambient(): Modifier = drawBehind {
    drawRect(Bg)
    drawRect(Brush.radialGradient(listOf(Color(0x59FF6A3D), Color.Transparent), Offset(size.width * 0.1f, size.height * 0.02f), size.maxDimension * 0.75f))
    drawRect(Brush.radialGradient(listOf(Color(0x2814B8A6), Color.Transparent), Offset(size.width * 0.95f, size.height * 0.9f), size.maxDimension * 0.7f))
}

// ---------------------------------------------------------------------------------------------------------------
// Text, icons
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
) {
    val c = if (color != Color.Unspecified) color else if (style.color != Color.Unspecified) style.color else LocalContentColor.current
    var st = if (style.brush != null) style else style.copy(color = c)
    if (fontFamily != null) st = st.copy(fontFamily = fontFamily)
    if (textAlign != null) st = st.copy(textAlign = textAlign)
    BasicText(text, modifier, st, overflow = overflow, maxLines = maxLines)
}

@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Image(imageVector, contentDescription, modifier.size(24.dp), colorFilter = ColorFilter.tint(tint))
}

// ---------------------------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------------------------

@Composable
private fun ButtonRow(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentColor: Color,
    pad: Modifier,
    paint: Modifier,
    shape: Shape,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier.alpha(if (enabled) 1f else 0.4f)
                .focusClick(shape, onClick = { if (enabled) onClick() })
                .then(paint).then(pad),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    ButtonRow(
        onClick, modifier, enabled, OnAccent,
        Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        Modifier.background(Brush.linearGradient(listOf(Accent, AccentHi)), shape).rim(shape),
        shape, content,
    )
}

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    ButtonRow(onClick, modifier, enabled, Ink, Modifier.padding(horizontal = 20.dp, vertical = 11.dp), Modifier.glassFill(shape), shape, content)
}

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    ButtonRow(onClick, modifier, enabled, Accent, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), Modifier, RoundedCornerShape(50), content)
}

@Composable
fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = CircleShape
    Box(
        modifier.defaultMinSize(46.dp, 46.dp).focusClick(shape, onClick = onClick).glassFill(shape).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ---------------------------------------------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun CircularProgressIndicator(modifier: Modifier = Modifier, color: Color = Accent) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(modifier.size(36.dp)) {
        val w = 3.dp.toPx()
        val tl = Offset(w / 2, w / 2)
        val sz = Size(size.width - w, size.height - w)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, tl, sz, style = Stroke(w))
        drawArc(color, a, 100f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
fun LinearProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier, color: Color = Accent, trackColor: Color = Color(0x33FFFFFF)) {
    Box(modifier.background(trackColor, RoundedCornerShape(50))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress().coerceIn(0f, 1f)).background(color, RoundedCornerShape(50)))
    }
}

class SliderColors(val thumb: Color, val active: Color)

object SliderDefaults {
    fun colors(thumbColor: Color = Accent, activeTrackColor: Color = Accent) = SliderColors(thumbColor, activeTrackColor)
}

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    var w by remember { mutableIntStateOf(1) }
    val change by rememberUpdatedState(onValueChange)
    val done by rememberUpdatedState(onValueChangeFinished)
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val frac = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    fun at(x: Float) = valueRange.start + (x / w).coerceIn(0f, 1f) * span
    Box(
        modifier.fillMaxWidth().height(36.dp).onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { change(at(it.x)); done?.invoke() } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { change(at(it.x)) },
                    onDragEnd = { done?.invoke() },
                    onDragCancel = { done?.invoke() },
                ) { c, _ -> change(at(c.position.x)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF), RoundedCornerShape(50)))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).background(colors.active, RoundedCornerShape(50)))
        Box(Modifier.offset { IntOffset((frac * w).toInt() - 7.dp.roundToPx(), 0) }.size(14.dp).background(colors.thumb, CircleShape))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Input + dialogs
// ---------------------------------------------------------------------------------------------------------------

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) CompositionLocalProvider(LocalContentColor provides InkDim, LocalTextStyle provides Type.labelLarge) { label() }
        Box(
            Modifier.fillMaxWidth().background(Color(0x14FFFFFF), shape)
                .border(if (focused) 2.dp else 1.dp, if (focused) SolidColor(Accent) else Brush.linearGradient(listOf(Color(0x66FFFFFF), Color(0x1AFFFFFF))), shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            BasicTextField(
                value, onValueChange, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                singleLine = singleLine,
                textStyle = Type.bodyMedium.copy(color = Ink, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
            )
            if (value.isEmpty() && placeholder != null) {
                CompositionLocalProvider(LocalContentColor provides Color(0x80B0A8AE)) { placeholder() }
            }
        }
    }
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
) {
    val shape = RoundedCornerShape(28.dp)
    Dialog(onDismissRequest) {
        Column(
            modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xF22B2530), Color(0xF21A171D))), shape)
                .rim(shape)
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (title != null) CompositionLocalProvider(LocalTextStyle provides Type.titleMedium) { title() }
            if (text != null) text()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
