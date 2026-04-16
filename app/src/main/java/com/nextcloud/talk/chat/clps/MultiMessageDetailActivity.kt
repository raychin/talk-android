/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.clps

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.nextcloud.talk.R
import com.nextcloud.talk.activities.BaseActivity
import com.nextcloud.talk.adapters.items.clps.MessageMultiItem
import com.nextcloud.talk.chat.data.model.clps.MultiMessage
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.ActivityMessageMultiBinding
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem

/**
 * MultiMessage 详情页面，显示完整的转发消息列表
 */
class MultiMessageDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityMessageMultiBinding
    private lateinit var adapter: FlexibleAdapter<AbstractFlexibleItem<*>>
    private val itemsList = mutableListOf<AbstractFlexibleItem<*>>()

    var roomToken: String? = null
    private var messageId: Int = 0
    private var multiMessageJson: String? = null
    private var title: String? = null
    private var messageCount: Int = 0

    private lateinit var user: User
    var rootView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessageMultiBinding.inflate(layoutInflater)
        setSupportActionBar(binding.toolbar)
        setContentView(binding.root)
        rootView= binding.root

        user = currentUserProvider.currentUser.blockingGet()

        setupIntentData()
        setupToolbar()
        setupRecyclerView()
        loadMultiMessage()
    }

    private fun setupIntentData() {
        roomToken = intent.getStringExtra(KEY_ROOM_TOKEN)
        messageId = intent.getIntExtra(KEY_MESSAGE_ID, 0)
        multiMessageJson = intent.getStringExtra(KEY_MULTI_MESSAGE_JSON)
        title = intent.getStringExtra(KEY_TITLE)
        messageCount = intent.getIntExtra(KEY_MESSAGE_COUNT, 0)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        // 设置标题
        val displayTitle = if (!title.isNullOrBlank()) {
            title
        } else {
            getString(R.string.clps_multi_message_detail_title, messageCount)
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.title = displayTitle
    }

    private fun setupRecyclerView() {
        adapter = FlexibleAdapter(itemsList)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadMultiMessage() {
        if (multiMessageJson.isNullOrBlank()) {
            showErrorAndFinish()
            return
        }

        try {
            val gson = Gson()
            val multiMessage = gson.fromJson(multiMessageJson, MultiMessage::class.java)

            if (multiMessage == null || multiMessage.message.isNullOrEmpty()) {
                showErrorAndFinish()
                return
            }


            // binding.genericComposeView.apply {
            //     setContent {
            //         MultiChatView(context, multiMessage.message!!)
            //     }
            // }


            // 添加消息列表项
            multiMessage.message?.forEach { chatMessage ->
                itemsList.add(MessageMultiItem(this, user, chatMessage, false, viewThemeUtils))
            }

            if (adapter != null) {
                adapter.updateDataSet(itemsList)
            } else {
                setupRecyclerView()
            }

            // 更新消息数量提示
            binding.messageCountText.text = getString(
                R.string.clps_multi_message_total_count,
                multiMessage.message?.size ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading MultiMessage", e)
            showErrorAndFinish()
        }
    }

    private fun showErrorAndFinish() {
        binding.errorText.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.messageCountText.visibility = View.GONE

        Toast.makeText(this, R.string.clps_multi_message_load_error, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MultiMessageDetailAct"

        const val KEY_ROOM_TOKEN = "room_token"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_MULTI_MESSAGE_JSON = "multi_message_json"
        const val KEY_TITLE = "title"
        const val KEY_MESSAGE_COUNT = "message_count"

        fun newIntent(
            context: Context,
            roomToken: String,
            messageId: Int,
            multiMessageJson: String,
            title: String?,
            messageCount: Int
        ): Intent {
            return Intent(context, MultiMessageDetailActivity::class.java).apply {
                putExtra(KEY_ROOM_TOKEN, roomToken)
                putExtra(KEY_MESSAGE_ID, messageId)
                putExtra(KEY_MULTI_MESSAGE_JSON, multiMessageJson)
                putExtra(KEY_TITLE, title)
                putExtra(KEY_MESSAGE_COUNT, messageCount)
            }
        }
    }
}

