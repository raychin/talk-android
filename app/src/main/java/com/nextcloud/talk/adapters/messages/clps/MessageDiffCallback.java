/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.adapters.messages.clps;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.nextcloud.talk.chat.data.model.ChatMessage;
import com.stfalcon.chatkit.commons.models.IMessage;

import java.util.List;

public class MessageDiffCallback extends DiffUtil.Callback {

    private final List<IMessage> oldList;
    private final List<IMessage> newList;

    public MessageDiffCallback(List<IMessage> oldList, List<IMessage> newList) {
        this.oldList = oldList;
        this.newList = newList;
    }

    @Override
    public int getOldListSize() {
        return oldList.size();
    }

    @Override
    public int getNewListSize() {
        return newList.size();
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        IMessage oldItem = oldList.get(oldItemPosition);
        IMessage newItem = newList.get(newItemPosition);

        if (oldItem instanceof ChatMessage && newItem instanceof ChatMessage) {
            ChatMessage oldChatMessage = (ChatMessage) oldItem;
            ChatMessage newChatMessage = (ChatMessage) newItem;

            String oldId = String.valueOf(oldChatMessage.getJsonMessageId());
            String newId = String.valueOf(newChatMessage.getJsonMessageId());

            return oldId.equals(newId);
        }

        return oldItem.getId().equals(newItem.getId());
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        IMessage oldItem = oldList.get(oldItemPosition);
        IMessage newItem = newList.get(newItemPosition);

        if (oldItem instanceof ChatMessage && newItem instanceof ChatMessage) {
            ChatMessage oldChatMessage = (ChatMessage) oldItem;
            ChatMessage newChatMessage = (ChatMessage) newItem;

            String oldId = String.valueOf(oldChatMessage.getJsonMessageId());
            String newId = String.valueOf(newChatMessage.getJsonMessageId());

            // 如果消息ID相同，检查选择状态是否变化
            return oldId.equals(newId);
        }

        return oldItem.equals(newItem);
    }

    @NonNull
    @Override
    public Object getChangePayload(int oldItemPosition, int newItemPosition) {
        return "content_changed";
    }
}
