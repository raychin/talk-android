/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2022 Álvaro Brey <alvaro@alvarobrey.com>
 * SPDX-FileCopyrightText: 2022 Tim Krüger <t@timkrueger.me>
 * SPDX-FileCopyrightText: 2021 Andy Scherzinger <infoi@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2017-2019 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.adapters.items

import android.content.Context
import android.text.TextUtils
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.nextcloud.talk.R
import com.nextcloud.talk.data.message.model.MessageFilterType
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.RvItemSearchMessageBinding
import com.nextcloud.talk.extensions.loadImage
import com.nextcloud.talk.extensions.loadThumbnail
import com.nextcloud.talk.models.domain.SearchMessageEntry
import com.nextcloud.talk.ui.theme.ViewThemeUtils
import com.nextcloud.talk.utils.DateUtils
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFilterable
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.flexibleadapter.items.ISectionable
import eu.davidea.viewholders.FlexibleViewHolder
data class MessageResultItem(
    private val context: Context,
    private val currentUser: User,
    val messageEntry: SearchMessageEntry,
    var showHeader: Boolean = false,
    private val viewThemeUtils: ViewThemeUtils
) : AbstractFlexibleItem<MessageResultItem.ViewHolder>(),
    IFilterable<String>,
    ISectionable<MessageResultItem.ViewHolder, GenericTextHeaderItem> {

    class ViewHolder(view: View, adapter: FlexibleAdapter<*>) : FlexibleViewHolder(view, adapter) {
        var binding: RvItemSearchMessageBinding

        init {
            binding = RvItemSearchMessageBinding.bind(view)
        }
    }

    override fun getLayoutRes(): Int = R.layout.rv_item_search_message

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
        holder.binding.conversationTitle.text = messageEntry.title
        bindMessageExcerpt(holder)
        messageEntry.thumbnailURL?.let { holder.binding.thumbnail.loadThumbnail(it, currentUser) }

        // val thumbnailURL = if (TextUtils.isEmpty(messageEntry.thumbnailURL)) {
        //     null
        // } else {
        //     generateImageUrl(messageEntry.thumbnailURL!!)
        // }
        // thumbnailURL?.let { holder.binding.thumbnail.loadThumbnail(it, currentUser) }

        // 时间戳有问题，暂时不显示
        holder.binding.conversationTime.visibility = View.GONE
        holder.binding.conversationTime.text = DateUtils(context).getShowTimeString(messageEntry.timestamp!!)
        if (TextUtils.isEmpty(messageEntry.thumbnail)) {
            holder.binding.thumbnailImg.visibility = View.GONE
            holder.binding.thumbnailSize.visibility = View.GONE
        } else {
            holder.binding.thumbnailImg.visibility = View.VISIBLE
            holder.binding.thumbnailImg.loadImage(messageEntry.thumbnail!!, currentUser, null)
            holder.binding.thumbnailSize.visibility = View.VISIBLE
            holder.binding.thumbnailSize.text = formatFileSize(messageEntry.thumbnailSize!!)
        }
    }

    private fun formatFileSize(sizeInKB: Int): String {
        Log.e("Ray", "formatFileSize: $sizeInKB")
        return when {
            sizeInKB < 1024 -> "${sizeInKB}B"
            sizeInKB < 1024 * 1024 -> "${String.format("%.1f", (sizeInKB / 1024).toDouble())}KB"
            sizeInKB < 1024 * 1024 * 1024 -> "${String.format("%.1f", (sizeInKB / (1024 * 1024)).toDouble())}MB"
            else -> "${String.format("%.1f", (sizeInKB / (1024 * 1024 * 1024)).toDouble())}GB"
        }
    }


    private fun bindMessageExcerpt(holder: ViewHolder) {
        // TODO RAY 这里优化成遍历枚举
        val highText = messageEntry.searchTerm
            .replace(MessageFilterType.TEXT.value, "")
            .replace(MessageFilterType.IMAGE.value, "")
            .replace(MessageFilterType.FILE.value, "")

        viewThemeUtils.platform.highlightText(
            holder.binding.messageExcerpt,
            messageEntry.messageExcerpt,
            highText
        )
    }

    override fun filter(constraint: String?): Boolean = true

    override fun getItemViewType(): Int = VIEW_TYPE

    companion object {
        const val VIEW_TYPE = FlexibleItemViewType.MESSAGE_RESULT_ITEM
    }

    override fun getHeader(): GenericTextHeaderItem =
        MessagesTextHeaderItem(context, viewThemeUtils)
            .apply {
                // FlexibleAdapter needs this hack for some reason
                isHidden = showHeader
            }

    override fun setHeader(header: GenericTextHeaderItem?) {
        // nothing, header is always the same
    }
}
