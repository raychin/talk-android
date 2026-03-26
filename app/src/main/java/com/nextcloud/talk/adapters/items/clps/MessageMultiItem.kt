/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Álvaro Brey <alvaro@alvarobrey.com>
 * SPDX-FileCopyrightText: 2022 Tim Krüger <t@timkrueger.me>
 * SPDX-FileCopyrightText: 2021 Andy Scherzinger <infoi@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2017-2019 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.adapters.items.clps

import android.content.Context
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import androidx.core.text.toSpanned
import androidx.recyclerview.widget.RecyclerView
import com.nextcloud.talk.R
import com.nextcloud.talk.adapters.items.FlexibleItemViewType
import com.nextcloud.talk.adapters.items.GenericTextHeaderItem
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.data.message.model.MessageFilterType
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.RvItemMultiMessageBinding
import com.nextcloud.talk.databinding.RvItemSearchMessageBinding
import com.nextcloud.talk.extensions.loadAvatarOrImagePreview
import com.nextcloud.talk.extensions.loadFirstLetterAvatar
import com.nextcloud.talk.extensions.loadImage
import com.nextcloud.talk.extensions.loadThumbnail
import com.nextcloud.talk.ui.theme.ViewThemeUtils
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.ChatMessageUtils
import com.nextcloud.talk.utils.message.MessageUtils
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFilterable
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.flexibleadapter.items.ISectionable
import eu.davidea.viewholders.FlexibleViewHolder

data class MessageMultiItem(
    private val context: Context,
    private val currentUser: User,
    val messageEntry: ChatMessage,
    var showHeader: Boolean = false,
    private val viewThemeUtils: ViewThemeUtils
) : AbstractFlexibleItem<MessageMultiItem.ViewHolder>(),
    IFilterable<String>,
    ISectionable<MessageMultiItem.ViewHolder, GenericTextHeaderItem> {
    class ViewHolder(view: View, adapter: FlexibleAdapter<*>) : FlexibleViewHolder(view, adapter) {
        var binding: RvItemMultiMessageBinding
        init {
            binding = RvItemMultiMessageBinding.bind(view)
        }
    }

    override fun getLayoutRes(): Int = R.layout.rv_item_multi_message

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>
    ): ViewHolder = ViewHolder(view, adapter)

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: ViewHolder,
        position: Int,
        payloads: MutableList<Any>?
    ) {

        val messageUtils = MessageUtils(context)
        var processedMessageText = messageUtils.enrichChatMessageText(
            holder.binding.messageExcerpt.context,
            messageEntry,
            true,
            viewThemeUtils
        )

        holder.binding.conversationTitle.text = messageEntry.actorDisplayName

        messageEntry.actorId?.let {
            val url = ApiUtils.getUrlForAvatar(currentUser.baseUrl, it,false)
            // holder.binding.thumbnail.loadThumbnail(url, currentUser)
            holder.binding.thumbnail.loadAvatarOrImagePreview(url, currentUser, null)
        }

        ChatMessageUtils().setAvatarOnMessage(holder.binding.thumbnail, messageEntry, viewThemeUtils)

        // // val thumbnailURL = if (TextUtils.isEmpty(messageEntry.thumbnailURL)) {
        // //     null
        // // } else {
        // //     generateImageUrl(messageEntry.thumbnailURL!!)
        // // }
        // // thumbnailURL?.let { holder.binding.thumbnail.loadThumbnail(it, currentUser) }

        // fix: 时间显示问题
        holder.binding.conversationTime.text = DateUtils.getRelativeTimeSpanString(
            messageEntry.timestamp!! * MILLIES,
            System.currentTimeMillis(),
            0,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )

        holder.binding.thumbnailImg.visibility = View.GONE
        holder.binding.thumbnailSize.visibility = View.GONE
        holder.binding.messageExcerpt.visibility = View.GONE
        // "message": "{file}"
        if (messageEntry.message == "{file}") {
            holder.binding.thumbnailImg.visibility = View.VISIBLE
            holder.binding.thumbnailImg.loadImage(messageEntry.messageParameters?.get("file")?.get("path")!!,
                    currentUser, null)
            holder.binding.messageExcerpt.visibility = View.VISIBLE
            holder.binding.messageExcerpt.text = messageEntry.messageParameters?.get("file")?.get("name")
            holder.binding.thumbnailSize.visibility = View.VISIBLE
            holder.binding.thumbnailSize.text = formatFileSize(messageEntry.messageParameters?.get("file")?.get
                    ("size")!!)
        } else {
            holder.binding.messageExcerpt.visibility = View.VISIBLE
            // holder.binding.messageExcerpt.text = messageEntry.message
            processedMessageText = messageUtils.processMessageParameters(
                holder.binding.messageExcerpt.context,
                viewThemeUtils,
                processedMessageText!!.toSpanned(),
                messageEntry,
                holder.itemView
            )
            holder.binding.messageExcerpt.text = processedMessageText
        }
        // if (TextUtils.isEmpty(messageEntry.thumbnail)) {
        //     holder.binding.thumbnailImg.visibility = View.GONE
        //     holder.binding.thumbnailSize.visibility = View.GONE
        // } else {
        //     holder.binding.thumbnailImg.visibility = View.VISIBLE
        //     holder.binding.thumbnailImg.loadImage(messageEntry.thumbnail!!, currentUser, null)
        //     holder.binding.thumbnailSize.visibility = View.VISIBLE
        //     holder.binding.thumbnailSize.text = formatFileSize(messageEntry.thumbnailSize!!)
        // }
    }

    private fun formatFileSize(sizeInKBStr: String): String {
        val sizeInKB = sizeInKBStr.toLong()
        Log.e("Ray", "formatFileSize: $sizeInKB")
        return when {
            sizeInKB < 1024 -> "${sizeInKB}B"
            sizeInKB < 1024 * 1024 -> "${String.format("%.1f", (sizeInKB / 1024).toDouble())}KB"
            sizeInKB < 1024 * 1024 * 1024 -> "${String.format("%.1f", (sizeInKB / (1024 * 1024)).toDouble())}MB"
            else -> "${String.format("%.1f", (sizeInKB / (1024 * 1024 * 1024)).toDouble())}GB"
        }
    }


    override fun filter(constraint: String?): Boolean = true

    override fun getItemViewType(): Int = VIEW_TYPE

    companion object {
        const val VIEW_TYPE = FlexibleItemViewType.MESSAGE_RESULT_ITEM
        private const val MILLIES = 1000L
    }

    override fun getHeader(): GenericTextHeaderItem? = null

    override fun setHeader(header: GenericTextHeaderItem?) {
        // nothing, header is always the same
    }
}
