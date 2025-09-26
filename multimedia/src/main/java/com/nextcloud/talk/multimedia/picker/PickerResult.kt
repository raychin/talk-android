/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// multimedia-module/src/main/java/com/nextcloud/talk/multimedia/picker/PickerResult.kt
package com.nextcloud.talk.multimedia.picker

import android.net.Uri

data class PickerResult(
    val uris: List<Uri>,
    val cancelled: Boolean = false
)
