/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.adapters.messages.clps

import android.view.View
import android.widget.CheckBox
import com.nextcloud.talk.adapters.messages.CommonMessageInterface
import com.nextcloud.talk.adapters.messages.TalkMessagesListAdapter
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.chat.data.model.ChatMessage

/**
 * 消息复选框辅助工具类
 * 提供通用的消息选择功能，供所有消息 ViewHolder 使用
 */
object MessageCheckboxHelper {

    /**
     * 初始化消息复选框的选择模式
     *
     * @param messageCheckbox 复选框视图
     * @param rootView 根视图（用于设置点击事件和透明度）
     * @param message 当前消息对象
     * @param commonMessageInterface 通用消息接口
     */
    fun initMessageCheckbox(
        messageCheckbox: CheckBox,
        rootView: View,
        message: ChatMessage,
        commonMessageInterface: CommonMessageInterface
    ) {
        val chatActivity = commonMessageInterface as? ChatActivity ?: return
        val adapter = chatActivity.adapter as? TalkMessagesListAdapter<*> ?: return

        val isInSelectionMode = adapter.isSelectionMode

        // 根据选择模式显示或隐藏复选框
        messageCheckbox.visibility = if (isInSelectionMode) {
            View.VISIBLE
        } else {
            View.GONE
        }

        rootView.setOnClickListener {
            if (!isInSelectionMode) {
                return@setOnClickListener
            }
            commonMessageInterface.onSelectMessage(message)

            // 获取消息ID并检查选中状态，然后设置checkbox为相应状态
            val messageId = message.jsonMessageId.toString()
            val isSelected = adapter.isMessageSelected(messageId)
            messageCheckbox.isChecked = isSelected

            // 根据选择状态调整透明度
            val alpha = if (isSelected) 0.6f else 1.0f
            rootView.alpha = alpha
        }
    }

    /**
     * 更新复选框的选中状态
     *
     * @param messageCheckbox 复选框视图
     * @param rootView 根视图
     * @param message 当前消息对象
     * @param commonMessageInterface 通用消息接口
     */
    fun updateCheckboxState(
        messageCheckbox: CheckBox,
        rootView: View,
        message: ChatMessage,
        commonMessageInterface: CommonMessageInterface
    ) {
        val chatActivity = commonMessageInterface as? ChatActivity ?: return
        val adapter = chatActivity.adapter as? TalkMessagesListAdapter<*> ?: return

        val messageId = message.jsonMessageId.toString()
        val isSelected = adapter.isMessageSelected(messageId)
        messageCheckbox.isChecked = isSelected

        // 根据选择状态调整透明度
        val alpha = if (isSelected) 0.6f else 1.0f
        rootView.alpha = alpha
    }
}

