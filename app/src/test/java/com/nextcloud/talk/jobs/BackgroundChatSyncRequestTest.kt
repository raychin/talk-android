/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 CLPS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundChatSyncRequestTest {

    @Test
    fun `background sync preserves unread messages and pending notifications`() {
        val parameters = buildBackgroundChatSyncRequestParameters(newestMessageIdFromDb = 42)

        assertEquals(
            hashMapOf(
                "lookIntoFuture" to 1,
                "timeout" to 0,
                "setReadMarker" to 0,
                "markNotificationsAsRead" to 0,
                "limit" to 100,
                "includeLastKnown" to 1,
                "lastKnownMessageId" to 42
            ),
            parameters
        )
    }

    @Test
    fun `background sync without local messages omits last known message`() {
        val parameters = buildBackgroundChatSyncRequestParameters(newestMessageIdFromDb = 0)

        assertEquals(0, parameters["includeLastKnown"])
        assertFalse(parameters.containsKey("lastKnownMessageId"))
    }

    @Test
    fun `background sync requires keep notifications capability`() {
        assertTrue(canSafelySyncChatMessagesInBackground(listOf("chat-keep-notifications")))
        assertFalse(canSafelySyncChatMessagesInBackground(emptyList()))
        assertFalse(canSafelySyncChatMessagesInBackground(null))
    }
}
