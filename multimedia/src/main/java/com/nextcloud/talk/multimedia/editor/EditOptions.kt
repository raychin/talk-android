/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.multimedia.editor

/**
 * @Description: 类说明
 * @Author: ray
 * @Date: 30/8/25
 */
data class EditOptions(
    val filters: List<FilterType> = listOf(FilterType.NONE),
    val rotation: Int = 0,
    val brightness: Float = 1.0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f
)
