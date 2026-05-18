package com.example.hearth.ui

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hearth.state.CameraState
import com.example.hearth.state.EntitySnapshot
import com.example.hearth.state.SensorState
import com.example.hearth.state.roomLabel

// Native Coral source-frame size (what cx/cy in EntitySnapshot are relative to).
private const val SRC_FRAME_W = 640f
private const val SRC_FRAME_H = 480f

// Display size for each camera card.
private val THUMB_DISPLAY_W = 360.dp
private val THUMB_DISPLAY_H = 270.dp

// MJPEG live-feed server runs on Coral at this port (same host as MQTT broker).
private const val MJPEG_PORT = 8080

// Half-side (in original-frame px) of the synthetic marker box we draw around
// each EntitySnapshot. The Coral delta stream only carries the centre point
// (cx/cy), not a true bbox, so this is a visual marker, not a real detection
// rectangle.
private const val BOX_HALF_SRC_PX = 40f

/**
 * Live camera grid. For each camera in [state.cameras] renders a Material3
 * card containing the latest base64-JPEG thumbnail (decoded once per payload)
 * with bounding-box overlays for every tracked entity.
 */
@Composable
fun CameraLiveFeedPanel(
    state: SensorState,
    brokerHost: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Live cameras",
            style = MaterialTheme.typography.titleSmall,
        )
        if (state.cameras.isEmpty()) {
            Text(
                "no cameras reporting yet",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        state.cameras.forEach { (name, cs) ->
            CameraFeedCard(name = name, cs = cs, brokerHost = brokerHost)
        }
    }
}

@Composable
private fun CameraFeedCard(name: String, cs: CameraState, brokerHost: String) {
    Card(
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val summary = cs.entities.values.groupingBy { it.cls }.eachCount()
                .entries.joinToString(", ") { "${it.value}×${it.key}" }
            Text(
                "${roomLabel(name)} camera — $name",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (summary.isBlank()) "no entities visible" else summary,
                style = MaterialTheme.typography.bodySmall,
            )

            // Seg silhouette is now COMPOSITED INTO the MJPEG by Coral, so no
            // separate overlay is needed here. Boxes still ride MQTT.
            val mjpegUrl = "http://$brokerHost:$MJPEG_PORT/cam/$name"
            LiveFeedWithOverlays(
                mjpegUrl = mjpegUrl,
                segOverlay = null,
                entities = cs.entities.values.toList(),
            )
        }
    }
}

@Composable
private fun LiveFeedWithOverlays(
    mjpegUrl: String,
    segOverlay: ImageBitmap?,
    entities: List<EntitySnapshot>,
) {
    val density = LocalDensity.current
    val labelTextPx = with(density) { 11.dp.toPx() }

    Box(
        modifier = Modifier
            .size(width = THUMB_DISPLAY_W, height = THUMB_DISPLAY_H)
            .background(Color.Black, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // Bottom layer: MJPEG live feed via WebView (Android Chromium handles
        // multipart/x-mixed-replace natively at 15-30 FPS).
        AndroidView(
            modifier = Modifier.size(width = THUMB_DISPLAY_W, height = THUMB_DISPLAY_H),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    webViewClient = WebViewClient()
                    loadUrl(mjpegUrl)
                }
            },
            update = { view ->
                // Only reload if the URL actually changed — avoids reconnect storm.
                if (view.url != mjpegUrl) view.loadUrl(mjpegUrl)
            },
        )

        // Middle layer: person-segmentation silhouette (~5 FPS via MQTT).
        if (segOverlay != null) {
            Image(
                bitmap = segOverlay,
                contentDescription = "person segmentation",
                modifier = Modifier.size(width = THUMB_DISPLAY_W, height = THUMB_DISPLAY_H),
                contentScale = ContentScale.Fit,
            )
        }

        // Top layer: bounding boxes + labels from entity deltas (live, MQTT).
        Canvas(
            modifier = Modifier.size(width = THUMB_DISPLAY_W, height = THUMB_DISPLAY_H),
        ) {
            val canvasW = size.width
            val canvasH = size.height
            val sx = canvasW / SRC_FRAME_W
            val sy = canvasH / SRC_FRAME_H

            val boxColor = Color(0xFF4DD0E1)
            val labelPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = labelTextPx
                isAntiAlias = true
                setShadowLayer(2f, 0f, 0f, android.graphics.Color.BLACK)
            }
            val BOX_HALF_SRC_PX = 40f
            entities.forEach { ent ->
                val cx = ent.cx.toFloat() * sx
                val cy = ent.cy.toFloat() * sy
                val halfW = BOX_HALF_SRC_PX * sx
                val halfH = BOX_HALF_SRC_PX * sy
                val x = (cx - halfW).coerceIn(0f, canvasW)
                val y = (cy - halfH).coerceIn(0f, canvasH)
                val w = (2f * halfW).coerceAtMost(canvasW - x)
                val h = (2f * halfH).coerceAtMost(canvasH - y)
                drawRect(
                    color = boxColor,
                    topLeft = Offset(x, y),
                    size = Size(w, h),
                    style = Stroke(width = 2f),
                )
                val labelY = if (y > labelTextPx + 2f) y - 4f else y + h + labelTextPx
                drawContext.canvas.nativeCanvas.drawText(ent.cls, x, labelY, labelPaint)
            }
        }
    }
}

@Composable
private fun ThumbnailWithBoxes(
    bitmap: ImageBitmap?,
    segOverlay: ImageBitmap?,
    entities: List<EntitySnapshot>,
) {
    // Convert dp → px once here (inside a @Composable scope where LocalDensity is valid).
    val density = LocalDensity.current
    val labelTextPx = with(density) { 11.dp.toPx() }

    Box(
        modifier = Modifier
            .size(width = THUMB_DISPLAY_W, height = THUMB_DISPLAY_H)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(
                "no signal yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Box
        }

        Image(
            bitmap = bitmap,
            contentDescription = "camera thumbnail",
            modifier = Modifier.size(width = THUMB_DISPLAY_W, height = THUMB_DISPLAY_H),
            contentScale = ContentScale.Fit,
        )

        // Person-segmentation silhouette overlay (green, semitransparent).
        if (segOverlay != null) {
            Image(
                bitmap = segOverlay,
                contentDescription = "person segmentation",
                modifier = Modifier.size(width = THUMB_DISPLAY_W, height = THUMB_DISPLAY_H),
                contentScale = ContentScale.Fit,
            )
        }

        // Single overlay canvas: bbox rectangles + class-name labels.
        // EntitySnapshot.cx/cy are in the original 640x480 frame, so scale
        // them to canvas-px before drawing.
        Canvas(
            modifier = Modifier.size(width = THUMB_DISPLAY_W, height = THUMB_DISPLAY_H),
        ) {
            val canvasW = size.width
            val canvasH = size.height
            val sx = canvasW / SRC_FRAME_W
            val sy = canvasH / SRC_FRAME_H

            val boxColor = Color(0xFF4DD0E1)
            val labelPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = labelTextPx
                isAntiAlias = true
                setShadowLayer(2f, 0f, 0f, android.graphics.Color.BLACK)
            }

            entities.forEach { ent ->
                val cx = ent.cx.toFloat() * sx
                val cy = ent.cy.toFloat() * sy
                val halfW = BOX_HALF_SRC_PX * sx
                val halfH = BOX_HALF_SRC_PX * sy
                val x = (cx - halfW).coerceIn(0f, canvasW)
                val y = (cy - halfH).coerceIn(0f, canvasH)
                val w = (2f * halfW).coerceAtMost(canvasW - x)
                val h = (2f * halfH).coerceAtMost(canvasH - y)

                drawRect(
                    color = boxColor,
                    topLeft = Offset(x, y),
                    size = Size(w, h),
                    style = Stroke(width = 2f),
                )

                // Class label just above the box (or just below if at top edge).
                val labelY = if (y > labelTextPx + 2f) y - 4f else y + h + labelTextPx
                drawContext.canvas.nativeCanvas.drawText(ent.cls, x, labelY, labelPaint)
            }
        }
    }
}

private fun decodeThumb(b64: String): ImageBitmap? = try {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    bmp?.asImageBitmap()
} catch (t: Throwable) {
    null
}
