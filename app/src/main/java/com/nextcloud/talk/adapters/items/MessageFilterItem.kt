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
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.nextcloud.talk.R
import com.nextcloud.talk.data.message.model.MessageFilter
import com.nextcloud.talk.databinding.RvItemSearchMessageFilterBinding
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.davidea.viewholders.FlexibleViewHolder

data class MessageFilterItem(
    private val context: Context,
    private val filterItem: MessageFilter,
    private val messageFilterItemListener: MessageFilterItemListener
) : AbstractFlexibleItem<MessageFilterItem.ViewHolder>() {

    class ViewHolder(view: View, adapter: FlexibleAdapter<*>) : FlexibleViewHolder(view, adapter) {
        var binding: RvItemSearchMessageFilterBinding

        init {
            binding = RvItemSearchMessageFilterBinding.bind(view)
        }
    }

    override fun getLayoutRes(): Int = R.layout.rv_item_search_message_filter

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
        holder.binding.messageSearchFilterTitle.text = filterItem.filterText
        // holder.binding.messageSearchFilterContainer.setOnClickListener {
        //     messageFilterItemListener.onMessageFilterItemClicked(holder.binding.messageSearchFilterTitle, position,
        //         filterItem)
        // }
        // holder.itemView.setOnClickListener {
        //     messageFilterItemListener.onMessageFilterItemClicked(holder.itemView, position, filterItem)
        // }
    }

    override fun getItemViewType(): Int = VIEW_TYPE

    companion object {
        const val VIEW_TYPE = FlexibleItemViewType.MESSAGE_FILTER_ITEM
    }
}

interface MessageFilterItemListener {
    fun onMessageFilterItemClicked(view: View, position: Int, messageFilter: MessageFilter)
}
