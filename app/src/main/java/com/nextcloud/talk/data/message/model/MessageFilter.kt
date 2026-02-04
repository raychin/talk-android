/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2022 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2017-2020 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.data.message.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize


enum class MessageFilterType(val value: String) {
    TEXT(""),
    IMAGE("[TYPE:image"),
    FILE("[TYPE:file");

    companion object {
        fun fromValue(value: String): MessageFilterType? {
            return entries.firstOrNull { it.value == value }
        }
    }
}
@Parcelize
data class MessageFilter(
    var id: Long? = null,
    var filterText: String? = null,
    var filterType: MessageFilterType = MessageFilterType.TEXT
) : Parcelable {

    companion object {
        private val TAG = MessageFilter::class.simpleName
    }
}
