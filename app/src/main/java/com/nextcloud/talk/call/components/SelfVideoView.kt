/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.call.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAP_THRESHOLD = 20f // 像素，超过此距离视为拖动

@Composable
fun SelfVideoView(
    eglBase: EglBase.Context,
    videoTrack: VideoTrack?,
    isFrontCamera: Boolean,
    onSwitchCamera: () -> Unit
) {
    var renderer: SurfaceViewRenderer? = remember { null }
    val density = LocalDensity.current
    val viewWidthPx = with(density) { 86.dp.roundToPx() }
    val viewHeightPx = with(density) { 110.dp.roundToPx() }

    // 拖动偏移量（像素），默认定位在右上角
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 父容器尺寸（全屏，用于边界约束和默认位置计算）
    var parentWidth by remember { mutableIntStateOf(0) }
    var parentHeight by remember { mutableIntStateOf(0) }

    val viewWidthDp = 86.dp
    val viewHeightDp = 110.dp

    // 外层 Box：填满整个屏幕作为拖动画布 + 手势接收区域
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                parentWidth = coordinates.size.width
                parentHeight = coordinates.size.height
            }
    ) {
        // 内层 Box：固定大小 86dp x 110dp，通过 offset 控制位置
        Box(
            modifier = Modifier
                .offset {
                    if (parentWidth > 0 && parentHeight > 0 && offsetX == 0f && offsetY == 0f) {
                        // // 默认定位在右上角
                        // val defaultX = (parentWidth - 200).toFloat().coerceAtLeast(0f)
                        // val defaultX = 20f.coerceAtLeast(0f)
                        val defaultX = with(density) { 20.dp.toPx() }
                        return@offset IntOffset(defaultX.roundToInt(), 100)
                    }
                    IntOffset(offsetX.roundToInt(), offsetY.roundToInt())
                }
                .size(viewWidthDp, viewHeightDp)
                .pointerInput(onSwitchCamera, parentWidth, parentHeight) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Main)
                        var isDrag = false
                        var totalDx = 0f
                        var totalDy = 0f

                        down.consume()

                        do {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            event.changes.forEach { change ->
                                change.consume()

                                if (change.pressed) {
                                    val dx = change.position.x - change.previousPosition.x
                                    val dy = change.position.y - change.previousPosition.y
                                    totalDx += abs(dx)
                                    totalDy += abs(dy)

                                    if (!isDrag && (totalDx > TAP_THRESHOLD || totalDy > TAP_THRESHOLD)) {
                                        isDrag = true
                                    }

                                    if (isDrag) {
                                        val newX = (offsetX + dx).coerceIn(
                                            (-viewWidthPx / 2).toFloat(),
                                            (parentWidth - viewWidthPx / 2).coerceAtLeast(viewWidthPx).toFloat()
                                        )
                                        val newY = (offsetY + dy).coerceIn(
                                            0f,
                                            (parentHeight - viewHeightPx).coerceAtLeast(viewHeightPx).toFloat()
                                        )
                                        offsetX = newX
                                        offsetY = newY
                                    }
                                } else {
                                    if (!isDrag && totalDx < TAP_THRESHOLD && totalDy < TAP_THRESHOLD) {
                                        onSwitchCamera()
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        init(eglBase, null)
                        setMirror(false)
                        setZOrderMediaOverlay(true)
                        setEnableHardwareScaler(false)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        isClickable = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        renderer = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { it.setMirror(false) },
                onRelease = { view ->
                    videoTrack?.removeSink(view)
                    view.clearImage()
                    view.release()
                }
            )
        }
    }

    DisposableEffect(videoTrack) {
        videoTrack?.addSink(renderer)
        onDispose { videoTrack?.removeSink(renderer) }
    }
}
