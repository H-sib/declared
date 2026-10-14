package io.github.h_sib.declared.app.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

// The intro is one timeline, in seconds. Every scene reads the same clock, so a tap can jump to the end and nothing is out of step.
private const val SNAP = 3.8f

/** The manifest lines, typed at about 30 characters a second with a short pause after each, so every line can be read as it appears. */
private val MANIFEST = listOf("// every app declares what it can do", "some.app can", "  + read your screen", "  + read every notification", "  + draw over other apps", "you read this?  ")

/** When each manifest line starts and ends typing, in seconds on the intro's clock. */
private val TYPING: List<Pair<Float, Float>> = run {
    var at = 4.9f
    MANIFEST.map { text ->
        val from = at
        at += text.length / 30f
        (from to at).also { at += 0.15f }
    }
}
private val NEVER_FROM = TYPING.last().second + 0.2f
private val FLIP_FROM = NEVER_FROM + 0.95f
private val BEATS_FROM = FLIP_FROM + 0.5f
private val END = BEATS_FROM + 1.6f

/** Where [t] is between [from] and [to], from 0 to 1. */
private fun seg(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

/** An ease that overshoots a little and settles: the halves slide past their fit and click back. */
private fun outBack(p: Float): Float {
    val c = 1.70158f
    return 1f + (c + 1f) * (p - 1f).pow(3) + c * (p - 1f).pow(2)
}

private fun typed(text: String, p: Float) = text.take((text.length * p).toInt())

private class Declaration(val title: String, val line: String)

/** What apps declare, in a few words each. The tiles type them out as the phone draws itself. */
private val declarations = listOf(
    Declaration("Accessibility", "reads your screen"),
    Declaration("Notifications", "reads every alert"),
    Declaration("Overlay", "draws over apps"),
    Declaration("Installer", "installs other apps"),
    Declaration("Usage", "sees what you open"),
    Declaration("Messages", "reads your texts"),
)

private const val PANEL_WORDS = "An example. Some app can read your screen, read every notification and draw over other apps. Do you read this part? Never. Declared reads it every check."
private const val BEAT_ONE = "Declared reads the declarations."
private const val BEAT_TWO = "Then tells you in words you can use."
private const val COUNTERSIGN = "Also: the H is cut in two. Only the true halves fit."

/**
 * The story of the name, as a short sequence. A phone draws itself and its tiles type what apps declare. The two halves of the H fly in and
 * snap together, the name lands, and a small manifest types itself: an app declares what it can do, and you read this part: never. Declared
 * reads it every check. A tap skips to the end. It is shown once and under Help; with animations off in Android it is simply all there.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val reduced = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val clock = remember { Animatable(if (reduced) END else 0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var snapped by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) {
        if (!reduced) clock.animateTo(END, tween((END * 1000).toInt(), easing = LinearEasing))
    }
    val t = clock.value
    val done = t >= END - 0.001f
    LaunchedEffect(t >= SNAP) {
        if (t >= SNAP && !snapped) {
            snapped = true
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    BackHandler(onBack = onDone)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        BoxWithConstraints(
            Modifier.fillMaxSize().systemBarsPadding()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Skip ahead", role = Role.Button) {
                    if (!done) scope.launch { clock.snapTo(END) }
                },
        ) {
            val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
            BlueprintPhone(t, Modifier.align(Alignment.Center))
            Brand(t, heightPx, Modifier.align(Alignment.Center))
            Box(Modifier.fillMaxSize().padding(top = maxHeight * 0.36f, bottom = 88.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Story(t)
            }
            if (!done) {
                TextButton(onClick = onDone, Modifier.align(Alignment.TopEnd).padding(horizontal = 12.dp, vertical = 8.dp)) { Text("Skip") }
            }
            val buttonAlpha = seg(t, END - 0.4f, END)
            if (buttonAlpha > 0f) {
                Button(
                    onClick = onDone,
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth().heightIn(min = 54.dp).graphicsLayer { alpha = buttonAlpha },
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Continue", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/** The opening: a phone outline draws itself, then six tiles appear and type what an app can declare. It fades as the H arrives. */
@Composable
private fun BlueprintPhone(t: Float, modifier: Modifier = Modifier) {
    val leave = seg(t, 2.7f, 3.3f)
    if (leave >= 1f) return
    val draw = FastOutSlowInEasing.transform(seg(t, 0.2f, 1.7f))
    val glow = MaterialTheme.colorScheme.primary
    Box(modifier.size(252.dp, 344.dp).graphicsLayer { alpha = 1f - leave; scaleX = 1f - 0.5f * leave; scaleY = 1f - 0.5f * leave }) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val inset = 12.dp.toPx()
            val outline = Path().apply {
                addRoundRect(RoundRect(Rect(Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset)), CornerRadius(36.dp.toPx())))
            }
            val measure = PathMeasure().apply { setPath(outline, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * draw, part, true)
            for ((w, a) in listOf(10.dp to 0.05f, 7.dp to 0.08f, 4.dp to 0.14f)) drawPath(part, glow.copy(alpha = a), style = Stroke(w.toPx() * 2, cap = StrokeCap.Round))
            drawPath(part, glow, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(Modifier.padding(start = 28.dp, end = 28.dp, top = 52.dp, bottom = 44.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0..2) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0..1) DeclarationTile(declarations[row * 2 + col], t, 1.0f + (row * 2 + col) * 0.28f, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun DeclarationTile(d: Declaration, t: Float, start: Float, modifier: Modifier) {
    val appear = FastOutSlowInEasing.transform(seg(t, start, start + 0.35f))
    if (appear <= 0f) return
    val shape = RoundedCornerShape(12.dp)
    val cursor = (t * 3f).toInt() % 2 == 0
    val p = seg(t, start + 0.2f, start + 0.8f)
    Column(
        modifier.graphicsLayer { alpha = appear; translationY = (1f - appear) * 12.dp.toPx(); scaleX = 0.92f + 0.08f * appear; scaleY = 0.92f + 0.08f * appear }
            .clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(d.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(
            typed(d.line, p) + if (p in 0.01f..0.99f && cursor) "|" else "",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = DeclaredMono), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The mark and the name: the halves fly in with streaks and snap together with a ring and a light sweep, the name lands letter by letter, then both rise to make room. */
@Composable
private fun Brand(t: Float, heightPx: Float, modifier: Modifier = Modifier) {
    val join = outBack(seg(t, 2.9f, 3.8f))
    val impact = seg(t, SNAP, 4.7f)
    val lift = FastOutSlowInEasing.transform(seg(t, 4.7f, 5.5f))
    val arrive = seg(t, 2.8f, 3.1f)
    if (arrive <= 0f) return
    Column(
        modifier.graphicsLayer {
            translationY = -heightPx * 0.27f * lift
            scaleX = 1f - 0.3f * lift
            scaleY = 1f - 0.3f * lift
            // a short shake as the halves meet
            translationX = if (impact in 0.001f..0.999f) sin(impact * 48f) * (1f - impact) * 7.dp.toPx() else 0f
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ImpactMark(join, impact, seg(t, 4.3f, 5.2f), arrive, Modifier.size(150.dp))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            "Declared".forEachIndexed { i, c ->
                val p = FastOutSlowInEasing.transform(seg(t, 4.0f + i * 0.07f, 4.5f + i * 0.07f))
                Text(
                    c.toString(), Modifier.graphicsLayer { alpha = p; translationY = (1f - p) * 22.dp.toPx() },
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = DeclaredMono),
                )
            }
        }
    }
}

@Composable
private fun ImpactMark(join: Float, impact: Float, sweep: Float, arrive: Float, modifier: Modifier = Modifier) {
    val left = MaterialTheme.colorScheme.onSurface
    val right = MaterialTheme.colorScheme.primary
    Canvas(modifier.graphicsLayer { alpha = arrive }.clearAndSetSemantics { }) {
        val k = size.width / 24f
        fun poly(vararg p: Pair<Float, Float>) = Path().apply {
            moveTo(p[0].first * k, p[0].second * k)
            for (i in 1 until p.size) lineTo(p[i].first * k, p[i].second * k)
            close()
        }
        val l = poly(0f to 0f, 7f to 0f, 7f to 9f, 13f to 9f, 13f to 12f, 11f to 12f, 11f to 15f, 7f to 15f, 7f to 24f, 0f to 24f)
        val r = poly(17f to 0f, 24f to 0f, 24f to 24f, 17f to 24f, 17f to 15f, 12.2f to 15f, 12.2f to 12f, 14.2f to 12f, 14.2f to 9f, 17f to 9f)
        val apart = (1f - join) * 22f * k
        val settled = join.coerceAtMost(1f)
        // speed streaks behind each half while it is still travelling
        if (settled < 1f) {
            val len = (1f - settled) * 16f * k
            for (i in 0..4) {
                val y = (3f + i * 4.5f) * k
                drawLine(left.copy(alpha = 0.5f * (1f - settled)), Offset(-apart - len, y), Offset(-apart, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(right.copy(alpha = 0.6f * (1f - settled)), Offset(24f * k + apart, y), Offset(24f * k + apart + len, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        // the ring and the halo when they meet
        if (impact in 0.001f..0.999f) {
            drawCircle(right.copy(alpha = (1f - impact) * 0.55f), radius = size.width * (0.45f + 0.95f * impact), center = center, style = Stroke(3.dp.toPx() * (1f - impact) + 1f))
            val halo = Stroke(width = 8.dp.toPx() * sin(PI.toFloat() * impact), join = StrokeJoin.Round)
            drawPath(l, left.copy(alpha = 0.25f), style = halo)
            drawPath(r, right.copy(alpha = 0.45f), style = halo)
        }
        withTransform({ translate(-apart, 0f); rotate(-12f * (1f - settled), Offset(3.5f * k, 12f * k)) }) { drawPath(l, left) }
        withTransform({ translate(apart, 0f); rotate(12f * (1f - settled), Offset(20.5f * k, 12f * k)) }) { drawPath(r, right) }
        // a band of light crossing the finished mark
        if (sweep in 0.001f..0.999f) {
            val x = -size.width * 0.6f + sweep * size.width * 2.2f
            val band = Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent), Offset(x, 0f), Offset(x + size.width * 0.5f, size.height * 0.6f))
            drawPath(l, band)
            drawPath(r, band)
        }
    }
}

/** A manifest types itself, line by line: what an app can do, and "you read this part: never". Then Declared reads it, and says so in words. */
@Composable
private fun Story(t: Float) {
    val cursor = (t * 3f).toInt() % 2 == 0
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = DeclaredMono)
    val shape = RoundedCornerShape(14.dp)
    val look = Status.LOOK.color()
    val strong = MaterialTheme.colorScheme.onSurface

    @Composable
    fun line(text: String, from: Float, to: Float, color: Color = strong) {
        val p = seg(t, from, to)
        Text(typed(text, p).ifEmpty { " " } + if (p in 0.01f..0.99f && cursor) "|" else "", style = mono, color = color)
    }

    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().graphicsLayer { alpha = seg(t, 4.9f, 5.2f) }.clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(16.dp)
                .clearAndSetSemantics { contentDescription = PANEL_WORDS },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            line(MANIFEST[0], TYPING[0].first, TYPING[0].second, MaterialTheme.colorScheme.onSurfaceVariant)
            for (i in 1..4) line(MANIFEST[i], TYPING[i].first, TYPING[i].second)
            Spacer(Modifier.height(6.dp))
            val readP = seg(t, TYPING[5].first, TYPING[5].second)
            val neverP = seg(t, NEVER_FROM, NEVER_FROM + 0.2f)
            val flip = seg(t, FLIP_FROM, FLIP_FROM + 0.3f)
            Box {
                Row(Modifier.graphicsLayer { alpha = 1f - flip }) {
                    Text(typed("you read this?  ", readP).ifEmpty { " " }, style = mono)
                    Text(
                        "never", Modifier.graphicsLayer { alpha = neverP; translationX = if (neverP in 0.001f..0.999f) sin(neverP * 40f) * (1f - neverP) * 6.dp.toPx() else 0f },
                        style = mono.copy(fontWeight = FontWeight.Bold), color = look,
                    )
                }
                Row(Modifier.graphicsLayer { alpha = flip }) {
                    Text("Declared reads it?  ", style = mono)
                    Text("every check", style = mono.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        val one = seg(t, BEATS_FROM, BEATS_FROM + 0.4f)
        val two = seg(t, BEATS_FROM + 0.4f, BEATS_FROM + 0.8f)
        val three = seg(t, BEATS_FROM + 0.8f, BEATS_FROM + 1.2f)
        Text(
            BEAT_ONE, Modifier.fillMaxWidth().graphicsLayer { alpha = one; translationY = (1f - one) * 10.dp.toPx() },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            BEAT_TWO, Modifier.fillMaxWidth().graphicsLayer { alpha = two; translationY = (1f - two) * 10.dp.toPx() },
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            COUNTERSIGN, Modifier.fillMaxWidth().graphicsLayer { alpha = three },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}
