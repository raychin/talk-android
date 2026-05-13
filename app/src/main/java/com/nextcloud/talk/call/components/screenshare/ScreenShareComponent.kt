/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.call.components.screenshare

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.ParticipantUiState
import kotlinx.coroutines.delay
import org.webrtc.EglBase

private const val DELAY_SCREEN_SHARE_TOPBAR_ANIMATION: Long = 5000

@Composable
fun ScreenShareComponent(
    participantUiState: ParticipantUiState,
    eglBase: EglBase?,
    modifier: Modifier = Modifier,
    onCloseIconClick: () -> Unit
) {

    val context = LocalContext.current
    val activity = context as? Activity

    // 默认横屏显示 isLandscape = true，71-74注释放开
    var isLandscape by remember { mutableStateOf(false) }

    // // 设置默认方向为横屏
    // LaunchedEffect(Unit) {
    //     activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    // }

    // 方向切换时更新 Activity 方向
    LaunchedEffect(isLandscape) {
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(DELAY_SCREEN_SHARE_TOPBAR_ANIMATION)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        if (participantUiState.isScreenStreamEnabled && participantUiState.screenMediaStream != null) {
            WebRTCScreenShareComponent(
                mediaStream = participantUiState.screenMediaStream,
                eglBase = eglBase,
                onSingleTap = { controlsVisible = true }
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ScreenShareControls(
                nick = participantUiState.nick.orEmpty(),
                isLandscape = isLandscape,
                onOrientationToggle = { isLandscape = !isLandscape },
                onCloseClick = onCloseIconClick
            )
        }
    }
}

@Composable
private fun ScreenShareControls(nick: String, isLandscape: Boolean, onOrientationToggle: () -> Unit, onCloseClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                )
            )
            .padding(top = 8.dp, start = 12.dp, end = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = nick,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OrientationToggleButton(
                    isLandscape = isLandscape,
                    onClick = onOrientationToggle
                )

                CloseButton(onClick = onCloseClick)
            }
        }
    }
}
@Composable
private fun ScreenShareControls(nick: String, onCloseClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                )
            )
            .padding(top = 8.dp, start = 12.dp, end = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = nick,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            CloseButton(onClick = onCloseClick)
        }
    }
}

@Composable
private fun OrientationToggleButton(
    isLandscape: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(4.dp)
            .size(36.dp)
            .background(
                color = Color.Black.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
    ) {
        Icon(
            imageVector = if (isLandscape) {
                Icons.Default.ScreenLockRotation  // 横屏状态显示锁定旋转
            } else {
                Icons.Default.ScreenRotation  // 竖屏状态显示可旋转
            },
            contentDescription = stringResource(
                if (isLandscape) R.string.switch_to_portrait else R.string.switch_to_landscape
            ),
            tint = Color.White
        )
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(4.dp)
            .size(36.dp)
            .background(
                color = Color.Black.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_baseline_close_24),
            contentDescription = stringResource(R.string.close),
            tint = Color.White
        )
    }
}

@Preview(name = "LTR")
@Composable
fun ScreenShareComponentPreview(nick: String = "Alice") {
    ScreenShareComponent(
        participantUiState = ParticipantUiState(
            sessionKey = null,
            baseUrl = "https://nextcloud.example.com",
            roomToken = "token",
            nick = nick,
            isConnected = true,
            isAudioEnabled = true,
            isStreamEnabled = true,
            isScreenStreamEnabled = false,
            raisedHand = false,
            isInternal = false
        ),
        eglBase = null,
        onCloseIconClick = {}
    )
}

@Preview(name = "RTL / Arabic", locale = "ar")
@Composable
fun ScreenShareComponentRtlPreview() {
    ScreenShareComponentPreview(nick = "أليس")
}
