/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 CLPS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.jobs

private const val CHAT_KEEP_NOTIFICATIONS_CAPABILITY = "chat-keep-notifications"

internal fun canSafelySyncChatMessagesInBackground(spreedFeatures: List<String>?): Boolean =
    spreedFeatures?.contains(CHAT_KEEP_NOTIFICATIONS_CAPABILITY) == true

internal fun buildBackgroundChatSyncRequestParameters(newestMessageIdFromDb: Long): HashMap<String, Int> {
    val parameters = hashMapOf(
        "lookIntoFuture" to 1,
        "timeout" to 0,
        "setReadMarker" to 0,
        "markNotificationsAsRead" to 0,
        "limit" to 100,
        "includeLastKnown" to if (newestMessageIdFromDb > 0) 1 else 0
    )

    if (newestMessageIdFromDb > 0) {
        parameters["lastKnownMessageId"] = newestMessageIdFromDb.toInt()
    }

    return parameters
}
