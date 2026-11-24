package org.geocrew.feature.record

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.geocrew.core.model.Sketch
import org.geocrew.core.model.SketchStroke

/** Draws strokes onto an Android canvas of [w] × [h] pixels; shared by the editor and the saved image. */
internal object SketchRender {
    fun draw(canvas: android.graphics.Canvas, strokes: List<SketchStroke>, w: Float, h: Float) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            paint.color = s.color.toInt()
            paint.strokeWidth = s.width * w
            if (s.points.size == 1) {
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(s.points[0].first * w, s.points[0].second * h, paint.strokeWidth / 2, paint)
                paint.style = android.graphics.Paint.Style.STROKE
                continue
            }
            // Through the midpoints, with the points as control points: smooth without overshoot.
            val path = android.graphics.Path()
            val pts = s.points.map { it.first * w to it.second * h }
            path.moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size - 1) {
                val (x, y) = pts[i]
                val (nx, ny) = pts[i + 1]
                path.quadTo(x, y, (x + nx) / 2, (y + ny) / 2)
            }
            path.lineTo(pts.last().first, pts.last().second)
            canvas.drawPath(path, paint)
        }
    }
}

/**
 * Full-screen field sketch (野外素描): a white page, or [background] (a
 * photo) to draw on. A drag draws a stroke, a tap a dot. Strokes are kept
 * in fractions of the page, so the saved image is drawn at full size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SketchEditor(background: Bitmap?, onSave: (List<SketchStroke>) -> Unit, onCancel: () -> Unit) {
    val strokes = remember { mutableStateListOf<SketchStroke>() }
    val current = remember { mutableStateListOf<Pair<Float, Float>>() }
    var color by remember { mutableLongStateOf(Sketch.COLORS[0].second) }
    var width by remember { mutableFloatStateOf(Sketch.WIDTHS[1].second) }
    var leaving by remember { mutableStateOf(false) }
    val image = remember(background) { background?.asImageBitmap() }
    val ratio = background?.let { it.width.toFloat() / it.height } ?: (Sketch.BLANK_WIDTH.toFloat() / Sketch.BLANK_HEIGHT)
    val leave = { if (strokes.isEmpty()) onCancel() else leaving = true }

    Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.navigationBarsPadding()) {
                TopAppBar(
                    title = { Text(if (background == null) "野外素描" else "在照片上画") },
                    navigationIcon = { IconButton(onClick = leave) { Icon(Icons.Default.Close, "关闭") } },
                    actions = {
                        IconButton(enabled = strokes.isNotEmpty(), onClick = { strokes.removeAt(strokes.lastIndex) }) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "撤销")
                        }
                        TextButton(enabled = strokes.isNotEmpty(), onClick = { onSave(strokes.toList()) }) { Text("保存") }
                    },
                )
                Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    Canvas(
                        Modifier.aspectRatio(ratio)
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .pointerInput(color, width) {
                                detectTapGestures { o -> strokes += SketchStroke(listOf(o.x / size.width to o.y / size.height), color, width * 1.6f) }
                            }
                            .pointerInput(color, width) {
                                detectDragGestures(
                                    onDragStart = { o -> current.clear(); current += o.x / size.width to o.y / size.height },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        current += change.position.x / size.width to change.position.y / size.height
                                    },
                                    onDragEnd = {
                                        if (current.isNotEmpty()) strokes += SketchStroke(Sketch.simplify(current.toList()), color, width)
                                        current.clear()
                                    },
                                    onDragCancel = { current.clear() },
                                )
                            },
                    ) {
                        image?.let {
                            drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()))
                        }
                        val live = if (current.isEmpty()) strokes else strokes + SketchStroke(current.toList(), color, width)
                        drawIntoCanvas { c -> SketchRender.draw(c.nativeCanvas, live, size.width, size.height) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sketch.COLORS.forEach { (name, argb) ->
                        val chosen = argb == color
                        Box(
                            Modifier.size(32.dp)
                                .background(Color(argb), CircleShape)
                                .border(if (chosen) 3.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { color = argb },
                        )
                        if (chosen) Text(name, style = MaterialTheme.typography.labelSmall)
                    }
                    Sketch.WIDTHS.forEach { (name, w) ->
                        FilterChip(selected = w == width, onClick = { width = w }, label = { Text(name) })
                    }
                    TextButton(enabled = strokes.isNotEmpty(), onClick = { strokes.clear() }) { Text("清空") }
                }
            }
        }
    }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("放弃这张素描？") },
            text = { Text("还没有保存，关闭后画的内容会丢掉。") },
            confirmButton = { TextButton(onClick = { leaving = false; onCancel() }) { Text("放弃") } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("继续画") } },
        )
    }
}
