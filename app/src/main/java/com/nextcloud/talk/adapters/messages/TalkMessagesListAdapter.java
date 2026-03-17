/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Christian Reiner <foss@christian-reiner.info>
 * SPDX-FileCopyrightText: 2020 Tobias Kaminsky <tobias.kaminsky@nextcloud.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.adapters.messages;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;

import com.nextcloud.talk.R;
import com.nextcloud.talk.chat.ChatActivity;
import com.nextcloud.talk.chat.data.model.ChatMessage;
import com.stfalcon.chatkit.commons.ImageLoader;
import com.stfalcon.chatkit.commons.ViewHolder;
import com.stfalcon.chatkit.commons.models.IMessage;
import com.stfalcon.chatkit.messages.MessageHolders;
import com.stfalcon.chatkit.messages.MessagesListAdapter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TalkMessagesListAdapter<M extends IMessage> extends MessagesListAdapter<M> {
    private final ChatActivity chatActivity;

    // 多选功能
    private boolean selectionMode = false;
    private final Set<String> selectedMessageIds = new HashSet<>();
    private OnSelectionChangeListener selectionChangeListener;
    public interface OnSelectionChangeListener {
        void onSelectionChange(Set<String> selectedIds);
        void onSelectionModeChanged(boolean isSelectionMode);
    }
    public void setOnSelectionChangeListener(OnSelectionChangeListener listener) {
        this.selectionChangeListener = listener;
    }
    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean selectionMode) {
        if (this.selectionMode != selectionMode) {
            this.selectionMode = selectionMode;
            if (!selectionMode) {
                selectedMessageIds.clear();
            }
            notifyDataSetChanged();
            if (selectionChangeListener != null) {
                selectionChangeListener.onSelectionModeChanged(selectionMode);
            }
        }
    }

    public void toggleFirstMessageSelection(ChatMessage chatMessage) {
        String id = String.valueOf(chatMessage.getJsonMessageId());
        selectedMessageIds.add(id);

        // 这里如果没有选择数据，则会关闭多选模式
//        if (selectedMessageIds.isEmpty()) {
//            setSelectionMode(false);
//        } else if (!selectionMode) {
//            setSelectionMode(true);
//        } else {
//            notifyDataSetChanged();
//        }

        if (selectionChangeListener != null) {
            selectionChangeListener.onSelectionChange(selectedMessageIds);
        }
    }
    public void toggleMessageSelection(ChatMessage chatMessage) {
        String id = String.valueOf(chatMessage.getJsonMessageId());
        if (selectedMessageIds.contains(id)) {
            selectedMessageIds.remove(id);
        } else {
            selectedMessageIds.add(id);
        }

        // 这里如果没有选择数据，则会关闭多选模式
//        if (selectedMessageIds.isEmpty()) {
//            setSelectionMode(false);
//        } else if (!selectionMode) {
//            setSelectionMode(true);
//        } else {
//            notifyDataSetChanged();
//        }

        if (selectionChangeListener != null) {
            selectionChangeListener.onSelectionChange(selectedMessageIds);
        }
    }

    public boolean isMessageSelected(String messageId) {
        return selectedMessageIds.contains(messageId);
    }

    public Set<String> getSelectedMessageIds() {
        return selectedMessageIds;
    }

    public int getSelectedCount() {
        return selectedMessageIds.size();
    }

    public void clearSelection() {
        selectedMessageIds.clear();
        setSelectionMode(false);
    }

    private void updateSelectionUI(View itemView, int position) {
        if (items == null || position >= items.size()) {
            return;
        }

        ChatMessage message = (ChatMessage) items.get(position).item;
        String messageId = String.valueOf(message.getJsonMessageId());
        boolean isSelected = isMessageSelected(messageId);

        // 设置选择状态背景
        itemView.setSelected(isSelected);

        // 根据选择状态调整透明度
        float alpha = isSelected ? 0.6f : 1.0f;
        itemView.setAlpha(alpha);

        CheckBox checkBox = itemView.findViewById(R.id.messageCheckbox);
        if (checkBox != null) {
            checkBox.setChecked(isSelected);
        }
    }
    // ... existing code ...

    public TalkMessagesListAdapter(
        String senderId,
        MessageHolders holders,
        ImageLoader imageLoader,
        ChatActivity chatActivity) {
        super(senderId, holders, imageLoader);
        this.chatActivity = chatActivity;

//        // ... existing code ...
//        //  TODO RAY 设置长按监听器以进入选择模式，暂时不适用
//        setOnMessageViewLongClickListener((view, message) -> {
//            if (message instanceof ChatMessage) {
//                ChatMessage chatMessage = (ChatMessage) message;
//                // 如果已经在选择模式，切换选择状态
//                if (isSelectionMode()) {
//                    toggleMessageSelection(chatMessage.getId().toString());
//                } else {
//                    // 否则进入选择模式并选中当前消息
//                    toggleMessageSelection(chatMessage.getId().toString());
//                }
//            }
//            return true;
//        });
    }
    
    public List<MessagesListAdapter.Wrapper> getItems() {
        return items;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {

        if (holder instanceof IncomingTextMessageViewHolder holderInstance) {
            holderInstance.assignCommonMessageInterface(chatActivity);

            updateSelectionUI(holder.itemView, position);
        } else if (holder instanceof OutcomingTextMessageViewHolder holderInstance) {
            holderInstance.assignCommonMessageInterface(chatActivity);
            holderInstance.adjustIfNoteToSelf(chatActivity.getCurrentConversation());

        } else if (holder instanceof IncomingLocationMessageViewHolder holderInstance) {
            holderInstance.assignCommonMessageInterface(chatActivity);
        } else if (holder instanceof OutcomingLocationMessageViewHolder holderInstance) {
            holderInstance.assignCommonMessageInterface(chatActivity);
            holderInstance.adjustIfNoteToSelf(chatActivity.getCurrentConversation());

        } else if (holder instanceof IncomingLinkPreviewMessageViewHolder holderInstance) {
            holderInstance.assignCommonMessageInterface(chatActivity);
        } else if (holder instanceof OutcomingLinkPreviewMessageViewHolder holderInstance) {
            holderInstance.assignCommonMessageInterface(chatActivity);
            holderInstance.adjustIfNoteToSelf(chatActivity.getCurrentConversation());

        } else if (holder instanceof IncomingVoiceMessageViewHolder holderInstance) {
            holderInstance.assignVoiceMessageInterface(chatActivity);
            holderInstance.assignCommonMessageInterface(chatActivity);
        } else if (holder instanceof OutcomingVoiceMessageViewHolder holderInstance) {
            holderInstance.assignVoiceMessageInterface(chatActivity);
            holderInstance.assignCommonMessageInterface(chatActivity);
            holderInstance.adjustIfNoteToSelf(chatActivity.getCurrentConversation());

        } else if (holder instanceof PreviewMessageViewHolder holderInstance) {
            holderInstance.assignPreviewMessageInterface(chatActivity);
            holderInstance.assignCommonMessageInterface(chatActivity);

        } else if (holder instanceof SystemMessageViewHolder holderInstance) {
            holderInstance.assignSystemMessageInterface(chatActivity);

        } else if (holder instanceof IncomingDeckCardViewHolder holderInstance) {
            holderInstance.assignCommonMessageInterface(chatActivity);
        } else if (holder instanceof OutcomingDeckCardViewHolder holderInstance) {
            holderInstance.assignCommonMessageInterface(chatActivity);
            holderInstance.adjustIfNoteToSelf(chatActivity.getCurrentConversation());

        } else if (holder instanceof IncomingPollMessageViewHolder holderInstance) {
            holderInstance.assignCommonMessageInterface(chatActivity);
        } else if (holder instanceof OutcomingPollMessageViewHolder holderInstance) {
            holderInstance.assignCommonMessageInterface(chatActivity);
            holderInstance.adjustIfNoteToSelf(chatActivity.getCurrentConversation());
        }

        super.onBindViewHolder(holder, position);
    }
}
