/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.conversationlist.clps

/**
 * 会话列表刷新事件
 * 当收到新消息通知时，触发此事件以刷新会话列表
 *
 * @param userId 用户ID，用于匹配当前登录用户
 */
data class ConversationListRefreshEvent(
    val userId: Long
)
