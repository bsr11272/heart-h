package com.example.hearth.ui

import android.graphics.Paint
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hearth.state.RoomState
import com.example.hearth.state.SensorState
import com.example.hearth.state.roomLabel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

private const val FOV_DEGREES = 120.0
private const val X_MIN = -2.5
private const val X_MAX = 2.5
private const val Y_MIN = 0.0
private const val Y_MAX = 5.0

private const val TRAIL_LEN = 15
private const val TRAIL_HUE_OLDEST = 0f
private const val TRAIL_HUE_NEWEST = 120f

private const val HEAT_W = 50
private const val HEAT_H = 50
private const val HEAT_DECAY = 0.985f
private const val HEAT_MAX = 30f

private const val PARTICLE_LIFETIME_MS = 500L
private const val PARTICLE_BURST_COUNT = 8
private const val FAST_MOTION_THRESHOLD_M = 0.5

private val TacticalBackground = Color(0xFF050805)
private val TacticalGreen = Color(0xFF00FF7F)
private val TacticalGridGreen = Color(0xFF003322)
private val TacticalDimGreen = Color(0xFF006633)
private val TacticalAmber = Color(0xFFFFB300)
private val TacticalRed = Color(0xFFFF3344)
private const val TacticalScanlineAlpha = 0.05f

private data class Particle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val birthMs: Long,
)

@Composable
fun RadarMapPanel(state: SensorState, modifier: Modifier = Modifier) {
    val trails = remember {
        mutableStateMapOf<String, ArrayDeque<List<Pair<Double, Double>>>>()
    }
    val heatmaps = remember {
        mutableStateMapOf<String, Array<FloatArray>>()
    }
    val particles = remember {
        mutableStateMapOf<String, SnapshotStateList<Particle>>()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.rooms.isEmpty()) {
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(12.dp).fillMaxWidth()) {
                    Text("Radar map", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "No radar rooms reporting yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            state.rooms.forEach { (room, rs) ->
                LaunchedEffect(room, rs.targetsXY, state.tStamp) {
                    val deque = trails.getOrPut(room) { ArrayDeque() }
                    val prev = deque.firstOrNull()
                    deque.addFirst(rs.targetsXY)
                    while (deque.size > TRAIL_LEN) deque.removeLast()

                    val grid = heatmaps.getOrPut(room) {
                        Array(HEAT_H) { FloatArray(HEAT_W) }
                    }
                    rs.targetsXY.forEach { (x, y) ->
                        val cell = worldToHeatCell(x, y) ?: return@forEach
                        val gx = cell.first
                        val gy = cell.second
                        grid[gy][gx] = (grid[gy][gx] + 1f).coerceAtMost(HEAT_MAX)
                    }
                    for (j in 0 until HEAT_H) {
                        val row = grid[j]
                        for (i in 0 until HEAT_W) {
                            row[i] *= HEAT_DECAY
                            if (row[i] < 0.01f) row[i] = 0f
                        }
                    }

                    if (prev != null && prev.isNotEmpty() && rs.targetsXY.isNotEmpty()) {
                        val plist = particles.getOrPut(room) { mutableStateListOf() }
                        val nowMs = System.currentTimeMillis()
                        rs.targetsXY.forEach { (cx, cy) ->
                            var best = Double.MAX_VALUE
                            for ((px, py) in prev) {
                                val d = hypot(cx - px, cy - py)
                                if (d < best) best = d
                            }
                            if (best > FAST_MOTION_THRESHOLD_M && best < 5.0) {
                                spawnBurst(plist, cx.toFloat(), cy.toFloat(), nowMs)
                            }
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                var last = withFrameMillis { it }
                while (true) {
                    val now = withFrameMillis { it }
                    val dt = ((now - last).coerceAtLeast(0L)).toFloat() / 1000f
                    last = now
                    particles.values.forEach { plist ->
                        var i = 0
                        while (i < plist.size) {
                            val p = plist[i]
                            val age = now - p.birthMs
                            if (age > PARTICLE_LIFETIME_MS) {
                                plist.removeAt(i)
                            } else {
                                val nx = p.x + p.vx * dt
                                val ny = p.y + p.vy * dt
                                val drag = 0.92f
                                plist[i] = p.copy(
                                    x = nx,
                                    y = ny,
                                    vx = p.vx * drag,
                                    vy = p.vy * drag,
                                )
                                i++
                            }
                        }
                    }
                }
            }

            state.rooms.forEach { (room, rs) ->
                RadarRoomCard(
                    room = room,
                    rs = rs,
                    trail = trails[room] ?: ArrayDeque(),
                    heatmap = heatmaps[room],
                    particles = particles[room],
                )
            }
        }
    }
}

@Composable
private fun RadarRoomCard(
    room: String,
    rs: RoomState,
    trail: ArrayDeque<List<Pair<Double, Double>>>,
    heatmap: Array<FloatArray>?,
    particles: List<Particle>?,
) {
    val offline = rs.availability == "offline"

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0E0A))) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${roomLabel(room)} · radar [$room]",
                    style = MaterialTheme.typography.titleSmall,
                    color = TacticalGreen,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(rs = rs)
            }

            val moveLabel = when {
                !rs.presence -> "—"
                rs.moving -> "moving"
                else -> "still"
            }
            Text(
                "${rs.targetCount} targets · $moveLabel",
                style = MaterialTheme.typography.bodySmall,
                color = TacticalGreen.copy(alpha = 0.8f),
                fontFamily = FontFamily.Monospace,
            )

            val pulseTransition = rememberInfiniteTransition(label = "radar-pulse-$room")
            val pulse by pulseTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulse-$room",
            )

            val sweepTransition = rememberInfiniteTransition(label = "radar-sweep-$room")
            val sweep by sweepTransition.animateFloat(
                initialValue = -(FOV_DEGREES.toFloat() / 2f),
                targetValue = (FOV_DEGREES.toFloat() / 2f),
                animationSpec = infiniteRepeatable(
                    animation = tween(4000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "sweep-$room",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(
                        color = TacticalBackground,
                        shape = RoundedCornerShape(8.dp),
                    ),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRadarBackground()
                    if (heatmap != null) drawHeatmap(heatmap)
                    drawGrid()
                    drawFovCone()
                    if (!offline) {
                        drawSweep(sweep)
                    }
                    drawSensorMarker()
                    if (!offline) {
                        drawTrails(trail)
                        drawParticles(particles, System.currentTimeMillis())
                        drawTargets(rs.targetsXY, pulse)
                        drawHud(rs.targetsXY, online = true)
                    } else {
                        drawHud(emptyList(), online = false)
                    }
                    drawScanlines()
                    drawCompass()
                }

                if (offline) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "OFFLINE",
                            style = MaterialTheme.typography.titleMedium,
                            color = TacticalRed,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                } else if (rs.targetsXY.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "// NO CONTACT",
                            style = MaterialTheme.typography.bodySmall,
                            color = TacticalGreen.copy(alpha = 0.55f),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(rs: RoomState) {
    val (text, bg, fg) = when {
        rs.availability == "offline" ->
            Triple("OFFLINE", Color(0x33FF3344), TacticalRed)
        rs.presence ->
            Triple("PRESENCE", Color(0x3300FF7F), TacticalGreen)
        else ->
            Triple("STANDBY", Color(0x33006633), TacticalDimGreen)
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun DrawScope.drawRadarBackground() {
    drawRect(color = TacticalBackground)
}

private fun DrawScope.drawHeatmap(grid: Array<FloatArray>) {
    val cellW = size.width / HEAT_W.toFloat()
    val cellH = size.height / HEAT_H.toFloat()
    for (j in 0 until HEAT_H) {
        val row = grid[j]
        val py = size.height - (j + 1) * cellH
        for (i in 0 until HEAT_W) {
            val v = row[i]
            if (v < 0.05f) continue
            val norm = (v / HEAT_MAX).coerceIn(0f, 1f)
            val color = heatColor(norm)
            drawRect(
                color = color,
                topLeft = Offset(i * cellW, py),
                size = Size(cellW + 0.5f, cellH + 0.5f),
            )
        }
    }
}

private fun heatColor(norm: Float): Color {
    val alpha = (norm * 0.55f).coerceIn(0f, 0.55f)
    return when {
        norm < 0.33f -> Color(0f, 1f, 0f, alpha)
        norm < 0.66f -> Color(1f, 1f, 0f, alpha)
        else         -> Color(1f, 0f, 0f, alpha)
    }
}

private fun DrawScope.drawFovCone() {
    val w = size.width
    val h = size.height
    val origin = Offset(w / 2f, h)
    val halfFovRad = (FOV_DEGREES / 2.0) * PI / 180.0
    val r = h * 1.4f
    val leftX = origin.x + (-sin(halfFovRad) * r).toFloat()
    val leftY = origin.y - (cos(halfFovRad) * r).toFloat()
    val rightX = origin.x + (sin(halfFovRad) * r).toFloat()
    val rightY = origin.y - (cos(halfFovRad) * r).toFloat()

    val path = Path().apply {
        moveTo(origin.x, origin.y)
        lineTo(leftX, leftY)
        lineTo(rightX, rightY)
        close()
    }
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0x2200FF7F),
                Color(0x1100FF7F),
                Color(0x0000FF7F),
            ),
            startY = origin.y,
            endY = 0f,
        ),
    )
    drawPath(
        path = path,
        color = TacticalDimGreen.copy(alpha = 0.5f),
        style = Stroke(width = 1.2f),
    )
}

private fun DrawScope.drawSweep(angleDeg: Float) {
    val origin = Offset(size.width / 2f, size.height)
    val angleRad = angleDeg * PI.toFloat() / 180f
    val r = size.height * 1.4f
    val endX = origin.x + sin(angleRad) * r
    val endY = origin.y - cos(angleRad) * r
    drawLine(
        color = TacticalGreen.copy(alpha = 0.18f),
        start = origin,
        end = Offset(endX, endY),
        strokeWidth = 6f,
    )
    drawLine(
        color = TacticalGreen.copy(alpha = 0.9f),
        start = origin,
        end = Offset(endX, endY),
        strokeWidth = 1.5f,
    )
}

private fun DrawScope.drawGrid() {
    val gridColor = TacticalGridGreen.copy(alpha = 0.5f)
    val axisColor = TacticalDimGreen.copy(alpha = 0.7f)
    val xMetres = listOf(-2.5, -2.0, -1.0, 0.0, 1.0, 2.0, 2.5)
    for (x in xMetres) {
        val p1 = xyToCanvas(x, Y_MIN, size)
        val p2 = xyToCanvas(x, Y_MAX, size)
        drawLine(
            color = if (x == 0.0) axisColor else gridColor,
            start = p1,
            end = p2,
            strokeWidth = if (x == 0.0) 1.2f else 0.7f,
        )
    }
    val yMetres = listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
    for (y in yMetres) {
        val p1 = xyToCanvas(X_MIN, y, size)
        val p2 = xyToCanvas(X_MAX, y, size)
        drawLine(
            color = gridColor,
            start = p1,
            end = p2,
            strokeWidth = 0.7f,
        )
    }
    val origin = Offset(size.width / 2f, size.height)
    val pxPerMetreY = size.height / (Y_MAX - Y_MIN).toFloat()
    for (r in 1..5) {
        val radiusPx = r * pxPerMetreY
        drawCircle(
            color = TacticalGridGreen.copy(alpha = 0.45f),
            radius = radiusPx,
            center = origin,
            style = Stroke(width = 0.6f),
        )
    }
}

private fun DrawScope.drawSensorMarker() {
    val cx = size.width / 2f
    val cy = size.height
    val s = size.width * 0.022f
    val color = TacticalGreen
    val apex = Offset(cx, cy - s * 2f)
    val baseL = Offset(cx - s, cy - 2f)
    val baseR = Offset(cx + s, cy - 2f)
    drawLine(color, apex, baseL, strokeWidth = 2f)
    drawLine(color, apex, baseR, strokeWidth = 2f)
    drawLine(color, baseL, baseR, strokeWidth = 2f)
}

private fun DrawScope.drawTrails(trail: ArrayDeque<List<Pair<Double, Double>>>) {
    if (trail.isEmpty()) return
    val r = size.width * 0.011f
    trail.forEachIndexed { frameIdx, frame ->
        if (frameIdx == 0) return@forEachIndexed
        val age = frameIdx.toFloat()
        val alpha = exp(-age / 8f).coerceIn(0f, 1f)
        val t = (age / (TRAIL_LEN - 1).toFloat()).coerceIn(0f, 1f)
        val hue = TRAIL_HUE_NEWEST + (TRAIL_HUE_OLDEST - TRAIL_HUE_NEWEST) * t
        val color = Color.hsv(hue, 1f, 1f).copy(alpha = alpha)
        frame.forEach { (x, y) ->
            val cx = x.coerceIn(X_MIN, X_MAX)
            val cy = y.coerceIn(Y_MIN, Y_MAX)
            drawCircle(
                color = color,
                radius = r,
                center = xyToCanvas(cx, cy, size),
            )
        }
    }
}

private fun DrawScope.drawParticles(particles: List<Particle>?, nowMs: Long) {
    if (particles.isNullOrEmpty()) return
    val r = size.width * 0.006f
    particles.forEach { p ->
        val age = (nowMs - p.birthMs).coerceAtLeast(0L)
        if (age > PARTICLE_LIFETIME_MS) return@forEach
        val lifeT = age.toFloat() / PARTICLE_LIFETIME_MS.toFloat()
        val alpha = (1f - lifeT).coerceIn(0f, 1f)
        val cx = p.x.toDouble().coerceIn(X_MIN, X_MAX)
        val cy = p.y.toDouble().coerceIn(Y_MIN, Y_MAX)
        drawCircle(
            color = TacticalAmber.copy(alpha = alpha * 0.9f),
            radius = r,
            center = xyToCanvas(cx, cy, size),
        )
    }
}

private fun DrawScope.drawTargets(targets: List<Pair<Double, Double>>, pulse: Float) {
    val baseR = size.width * 0.018f
    val haloR = baseR * (2.2f + pulse * 0.8f)

    val labelPaint = Paint().apply {
        color = android.graphics.Color.rgb(0, 255, 127)
        textSize = size.width * 0.040f
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.rgb(0, 255, 127))
    }

    targets.forEachIndexed { i, (x, y) ->
        val cx = x.coerceIn(X_MIN, X_MAX)
        val cy = y.coerceIn(Y_MIN, Y_MAX)
        val pos = xyToCanvas(cx, cy, size)

        drawCircle(
            color = TacticalGreen.copy(alpha = 0.18f),
            radius = haloR,
            center = pos,
        )
        drawCircle(
            color = TacticalGreen.copy(alpha = 0.55f),
            radius = baseR * 1.6f,
            center = pos,
            style = Stroke(width = 1.3f),
        )
        drawCircle(
            color = Color.White,
            radius = baseR * 0.6f,
            center = pos,
        )
        drawCircle(
            color = TacticalGreen,
            radius = baseR * 0.95f,
            center = pos,
            style = Stroke(width = 1.5f),
        )

        val cross = 6f
        val gap = baseR * 1.1f
        drawLine(TacticalGreen, Offset(pos.x, pos.y - gap), Offset(pos.x, pos.y - gap - cross), strokeWidth = 1.5f)
        drawLine(TacticalGreen, Offset(pos.x, pos.y + gap), Offset(pos.x, pos.y + gap + cross), strokeWidth = 1.5f)
        drawLine(TacticalGreen, Offset(pos.x - gap, pos.y), Offset(pos.x - gap - cross, pos.y), strokeWidth = 1.5f)
        drawLine(TacticalGreen, Offset(pos.x + gap, pos.y), Offset(pos.x + gap + cross, pos.y), strokeWidth = 1.5f)

        val label = "T${i + 1}"
        val lx = pos.x + baseR * 1.9f
        val ly = pos.y - baseR * 0.9f
        drawContext.canvas.nativeCanvas.apply {
            drawText(label, lx, ly, labelPaint)
        }
    }
}

private fun DrawScope.drawHud(targets: List<Pair<Double, Double>>, online: Boolean) {
    val hudPaint = Paint().apply {
        color = if (online) android.graphics.Color.rgb(0, 255, 127)
                else        android.graphics.Color.rgb(255, 51, 68)
        textSize = size.width * 0.034f
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(4f, 0f, 0f, color)
    }
    val canvas = drawContext.canvas.nativeCanvas
    val status = if (online) "RADAR ACTIVE" else "STANDBY"
    canvas.drawText(status, size.width * 0.025f, size.width * 0.060f, hudPaint)

    val readPaint = Paint(hudPaint).apply {
        textSize = size.width * 0.028f
    }
    targets.take(4).forEachIndexed { i, (x, y) ->
        val line = "T%d  X=%+.1fm  Y=%+.1fm".format(i + 1, x, y)
        canvas.drawText(
            line,
            size.width * 0.025f,
            size.width * (0.105f + i * 0.038f),
            readPaint,
        )
    }
}

private fun DrawScope.drawCompass() {
    val cx = size.width * 0.90f
    val cy = size.width * 0.10f
    val r = size.width * 0.045f
    drawCircle(
        color = TacticalGreen.copy(alpha = 0.6f),
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = 1f),
    )
    val paint = Paint().apply {
        color = android.graphics.Color.rgb(0, 255, 127)
        textSize = size.width * 0.028f
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }
    val canvas = drawContext.canvas.nativeCanvas
    val pad = paint.textSize * 0.35f
    canvas.drawText("N", cx - paint.textSize * 0.3f, cy - r - pad, paint)
    canvas.drawText("S", cx - paint.textSize * 0.3f, cy + r + paint.textSize, paint)
    canvas.drawText("W", cx - r - paint.textSize, cy + paint.textSize * 0.35f, paint)
    canvas.drawText("E", cx + r + pad, cy + paint.textSize * 0.35f, paint)
}

private fun DrawScope.drawScanlines() {
    val color = TacticalGreen.copy(alpha = TacticalScanlineAlpha)
    var y = 0f
    val step = 4f
    while (y < size.height) {
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        y += step
    }
}

private fun spawnBurst(
    list: SnapshotStateList<Particle>,
    x: Float,
    y: Float,
    nowMs: Long,
) {
    val speed = 1.5f
    for (k in 0 until PARTICLE_BURST_COUNT) {
        val theta = (2.0 * PI * k / PARTICLE_BURST_COUNT).toFloat()
        list.add(
            Particle(
                x = x,
                y = y,
                vx = (cos(theta.toDouble()) * speed).toFloat(),
                vy = (sin(theta.toDouble()) * speed).toFloat(),
                birthMs = nowMs,
            )
        )
    }
    while (list.size > 256) list.removeAt(0)
}

fun xyToCanvas(x: Double, y: Double, size: Size): Offset {
    val xNorm = ((x - X_MIN) / (X_MAX - X_MIN)).toFloat()
    val yNorm = ((y - Y_MIN) / (Y_MAX - Y_MIN)).toFloat()
    val px = xNorm * size.width
    val py = size.height - yNorm * size.height
    return Offset(px, py)
}

private fun worldToHeatCell(x: Double, y: Double): Pair<Int, Int>? {
    if (x < X_MIN || x > X_MAX || y < Y_MIN || y > Y_MAX) return null
    val xNorm = (x - X_MIN) / (X_MAX - X_MIN)
    val yNorm = (y - Y_MIN) / (Y_MAX - Y_MIN)
    val gx = (xNorm * HEAT_W).toInt().coerceIn(0, HEAT_W - 1)
    val gy = (yNorm * HEAT_H).toInt().coerceIn(0, HEAT_H - 1)
    return gx to gy
}
