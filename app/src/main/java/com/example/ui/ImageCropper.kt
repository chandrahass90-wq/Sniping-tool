package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

@Composable
fun InteractiveCropper(
    modifier: Modifier = Modifier,
    onCropBoundsChanged: (Rect, Size) -> Unit
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var cropRect by remember { mutableStateOf<Rect?>(null) }
    
    // Initialize crop rect to cover 80% center when canvas size is resolved
    LaunchedEffect(canvasSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            val w = canvasSize.width * 0.85f
            val h = canvasSize.height * 0.5f
            val x = (canvasSize.width - w) / 2f
            val y = (canvasSize.height - h) / 2f
            val rect = Rect(x, y, x + w, y + h)
            cropRect = rect
            onCropBoundsChanged(rect, canvasSize)
        }
    }

    var dragAnchor by remember { mutableStateOf<DragAnchor?>(null) }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                canvasSize = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
            }
    ) {
        cropRect?.let { rect ->
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(rect, canvasSize) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragAnchor = getDragAnchor(offset, rect)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val currentAnchor = dragAnchor ?: return@detectDragGestures
                                val updatedRect = moveRect(rect, dragAmount, currentAnchor, canvasSize)
                                cropRect = updatedRect
                                onCropBoundsChanged(updatedRect, canvasSize)
                            },
                            onDragEnd = {
                                dragAnchor = null
                            }
                        )
                    }
            ) {
                // Draw 4 dark semi-transparent overlays to create a "hole" for the crop window
                // 1. Top overlay
                drawRect(
                    color = Color.Black.copy(alpha = 0.5f),
                    topLeft = Offset.Zero,
                    size = Size(size.width, max(0f, rect.top))
                )
                // 2. Bottom overlay
                drawRect(
                    color = Color.Black.copy(alpha = 0.5f),
                    topLeft = Offset(0f, rect.bottom),
                    size = Size(size.width, max(0f, size.height - rect.bottom))
                )
                // 3. Left overlay
                drawRect(
                    color = Color.Black.copy(alpha = 0.5f),
                    topLeft = Offset(0f, rect.top),
                    size = Size(max(0f, rect.left), rect.height)
                )
                // 4. Right overlay
                drawRect(
                    color = Color.Black.copy(alpha = 0.5f),
                    topLeft = Offset(rect.right, rect.top),
                    size = Size(max(0f, size.width - rect.right), rect.height)
                )

                // Draw dashed selection boundary
                drawRect(
                    color = Color.White,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
                    )
                )

                // Draw high-visibility corners using Material Green
                val handleSize = 16.dp.toPx()
                val handleThickness = 4.dp.toPx()
                val accentColor = Color(0xFF4CAF50)

                val corners = listOf(
                    Offset(rect.left, rect.top), // Top Left
                    Offset(rect.right, rect.top), // Top Right
                    Offset(rect.left, rect.bottom), // Bottom Left
                    Offset(rect.right, rect.bottom) // Bottom Right
                )

                corners.forEachIndexed { index, corner ->
                    val horizontalDirection = if (index % 2 == 0) 1f else -1f
                    val verticalDirection = if (index < 2) 1f else -1f

                    // Horizontal line
                    drawLine(
                        color = accentColor,
                        start = corner,
                        end = Offset(corner.x + handleSize * horizontalDirection, corner.y),
                        strokeWidth = handleThickness
                    )
                    // Vertical line
                    drawLine(
                        color = accentColor,
                        start = corner,
                        end = Offset(corner.x, corner.y + handleSize * verticalDirection),
                        strokeWidth = handleThickness
                    )
                }
            }
        }
    }
}

enum class DragAnchor {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER
}

private fun getDragAnchor(touchPoint: Offset, rect: Rect, thresholdDp: Float = 30f): DragAnchor {
    val threshold = thresholdDp * 3f
    val distTL = (touchPoint - Offset(rect.left, rect.top)).getDistance()
    val distTR = (touchPoint - Offset(rect.right, rect.top)).getDistance()
    val distBL = (touchPoint - Offset(rect.left, rect.bottom)).getDistance()
    val distBR = (touchPoint - Offset(rect.right, rect.bottom)).getDistance()

    return when {
        distTL < threshold -> DragAnchor.TOP_LEFT
        distTR < threshold -> DragAnchor.TOP_RIGHT
        distBL < threshold -> DragAnchor.BOTTOM_LEFT
        distBR < threshold -> DragAnchor.BOTTOM_RIGHT
        rect.contains(touchPoint) -> DragAnchor.CENTER
        else -> DragAnchor.CENTER
    }
}

private fun moveRect(rect: Rect, dragAmount: Offset, anchor: DragAnchor, boundary: Size): Rect {
    val minSize = 80f
    return when (anchor) {
        DragAnchor.TOP_LEFT -> {
            val newLeft = min(max(0f, rect.left + dragAmount.x), rect.right - minSize)
            val newTop = min(max(0f, rect.top + dragAmount.y), rect.bottom - minSize)
            Rect(newLeft, newTop, rect.right, rect.bottom)
        }
        DragAnchor.TOP_RIGHT -> {
            val newRight = max(min(boundary.width, rect.right + dragAmount.x), rect.left + minSize)
            val newTop = min(max(0f, rect.top + dragAmount.y), rect.bottom - minSize)
            Rect(rect.left, newTop, newRight, rect.bottom)
        }
        DragAnchor.BOTTOM_LEFT -> {
            val newLeft = min(max(0f, rect.left + dragAmount.x), rect.right - minSize)
            val newBottom = max(min(boundary.height, rect.bottom + dragAmount.y), rect.top + minSize)
            Rect(newLeft, rect.top, rect.right, newBottom)
        }
        DragAnchor.BOTTOM_RIGHT -> {
            val newRight = max(min(boundary.width, rect.right + dragAmount.x), rect.left + minSize)
            val newBottom = max(min(boundary.height, rect.bottom + dragAmount.y), rect.top + minSize)
            Rect(rect.left, rect.top, newRight, newBottom)
        }
        DragAnchor.CENTER -> {
            var newLeft = rect.left + dragAmount.x
            var newTop = rect.top + dragAmount.y
            val w = rect.width
            val h = rect.height

            if (newLeft < 0) newLeft = 0f
            if (newLeft + w > boundary.width) newLeft = boundary.width - w
            if (newTop < 0) newTop = 0f
            if (newTop + h > boundary.height) newTop = boundary.height - h

            Rect(newLeft, newTop, newLeft + w, newTop + h)
        }
    }
}

fun cropBitmap(original: Bitmap, relativeRect: Rect, canvasSize: Size): Bitmap {
    if (canvasSize.width <= 0f || canvasSize.height <= 0f) return original

    val scaleX = original.width.toFloat() / canvasSize.width
    val scaleY = original.height.toFloat() / canvasSize.height

    val left = (relativeRect.left * scaleX).toInt().coerceIn(0, original.width - 1)
    val top = (relativeRect.top * scaleY).toInt().coerceIn(0, original.height - 1)
    val right = (relativeRect.right * scaleX).toInt().coerceIn(0, original.width)
    val bottom = (relativeRect.bottom * scaleY).toInt().coerceIn(0, original.height)

    val width = (right - left).coerceAtLeast(10)
    val height = (bottom - top).coerceAtLeast(10)

    val finalWidth = min(width, original.width - left)
    val finalHeight = min(height, original.height - top)

    return Bitmap.createBitmap(original, left, top, finalWidth, finalHeight)
}
