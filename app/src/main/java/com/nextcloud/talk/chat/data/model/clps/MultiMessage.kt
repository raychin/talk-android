/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.data.model.clps

import com.nextcloud.talk.chat.data.model.ChatMessage

data class MultiMessage(
    var title: String? = null,
    var message: ArrayList<ChatMessage>? = null,
) {

}
