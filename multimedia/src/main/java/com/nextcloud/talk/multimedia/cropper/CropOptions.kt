/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// multimedia-module/src/main/java/com/nextcloud/talk/multimedia/cropper/CropOptions.kt
package com.nextcloud.talk.multimedia.cropper

data class CropOptions(
    val aspectRatio: Pair<Int, Int>? = null,
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val compressQuality: Int = 90,
    val circleCrop: Boolean = false,
    val hideBottomControls: Boolean = false
)
