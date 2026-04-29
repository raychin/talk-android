/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.chat.clps

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.GsonBuilder
import com.nextcloud.talk.R
// import autodagger.AutoInjector
// import com.nextcloud.talk.application.NextcloudTalkApplication
// import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.conversationlist.ConversationsListActivity
import com.nextcloud.talk.databinding.ChatBottomMessageMenuBinding
import com.nextcloud.talk.ui.theme.ViewThemeUtils
import com.nextcloud.talk.utils.bundle.BundleKeys
import javax.inject.Inject
import kotlin.String

// @AutoInjector(NextcloudTalkApplication::class)
class ChatBottomMessageMenuFragment : Fragment() {

    @Inject
    lateinit var viewThemeUtils: ViewThemeUtils

    lateinit var binding: ChatBottomMessageMenuBinding

    private lateinit var chatActivity: ChatActivity

    private var roomToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // sharedApplication!!.componentApplication.inject(this)
        chatActivity = requireActivity() as ChatActivity
        roomToken = arguments?.getString(BundleKeys.KEY_ROOM_TOKEN).orEmpty()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ChatBottomMessageMenuBinding.inflate(inflater)
        Log.d("Ray", "onViewCreated called, view visibility: ${binding.root.visibility}")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
    }

    private fun setupViews() {
        binding.menuShare.setOnClickListener {
            onShareSelectedMessages()
        }
    }

    private lateinit var selectedIds: Set<String>
    private lateinit var selectedMessages: ArrayList<ChatMessage>
    /**
     * 处理选中消息的分享
     */
    private fun onShareSelectedMessages() {
        // val selectedIds = chatActivity.getSelectedMessageIds()
        selectedIds = chatActivity.selectedMessageIds as Set<String>
        selectedMessages = chatActivity.selectedMessages as ArrayList<ChatMessage>
        if (selectedIds.isEmpty()) {
            Toast.makeText(chatActivity, R.string.clps_selector_no_text, Toast.LENGTH_SHORT).show()
            return
        }

        showForwardMenuBottomSheet(selectedIds)
    }

    /**
     * 显示转发菜单底部弹窗
     */
    private fun showForwardMenuBottomSheet(messageIds: Set<String>) {
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_forward_menu, null)

        val dialog = BottomSheetDialog(chatActivity, R.style.ThemeOverlay_App_BottomSheetDialog)
        dialog.setContentView(bottomSheetView)
        dialog.show()

        bottomSheetView.findViewById<TextView>(R.id.btn_forward_sequential).setOnClickListener {
            // 逐一转发
            forwardMessagesSequentially(messageIds)
            dialog.dismiss()
        }

        bottomSheetView.findViewById<TextView>(R.id.btn_forward_merged).setOnClickListener {
            // 合并转发
            forwardMessagesMerged(messageIds)
            dialog.dismiss()
        }

        bottomSheetView.findViewById<TextView>(R.id.btn_cancel).setOnClickListener {
            // 取消
            dialog.dismiss()
        }
    }

    private fun jsonString(): String {
        val multiMessage = SimplifiedMultiMessage()
        multiMessage.title = chatActivity.multiMessageTitle()
        // 转换为简化版本
        val simplifiedMessages = selectedMessages
            .sortedBy { it.timestamp * 1000 }
            // 根据 jsonMessageId 过滤掉重复的消息，只保留每个 ID 的第一条消息 add ray on 2026/04/29
            .distinctBy { it.jsonMessageId }
            .map { msg ->
                // 修复引用消息显示问题
                val parentMsg = extractParentMessageSafely(msg)

                // val messageType = EnumSystemMessageTypeConverter().convertToString(msg.systemMessageType)
                SimplifiedChatMessage(
                    jsonMessageId = msg.jsonMessageId,
                    timestamp = msg.timestamp,
                    message = msg.message,
                    actorDisplayName = msg.actorDisplayName,
                    actorType = msg.actorType,
                    actorId = msg.actorId,
                    messageType = msg.messageType,
                    messageParameters = if (msg.messageParameters != null) {
                        msg.messageParameters
                    } else {
                        HashMap()
                    },
                    parentMessageId = msg.parentMessageId,
                    reactions = if (msg.reactions != null) {
                        msg.reactions
                    } else {
                        LinkedHashMap()
                    },
                    isTemporary = msg.isTemporary,
                    referenceId = msg.referenceId,
                    id = msg.id,
                    expirationTimestamp = 0,
                    isReplyable = msg.replyable,
                    markdown = msg.renderMarkdown,
                    systemMessage = msg.getSystemMessage(),
                    token = msg.token,
                    parent = parentMsg
                )
            }
            .toCollection(ArrayList())
        multiMessage.message = simplifiedMessages
        // fix: 当 messageParameters 为 null 时，Gson 序列化会跳过这个字段，messageParameters 为空 HashMap，也会在 JSON 中保留该字段（显示为"messageParameters": {}）
        val gson = GsonBuilder().serializeNulls().create()
        return gson.toJson(multiMessage)
        // return Gson().toJson(multiMessage)
    }

    /**
     * 安全地提取父消息，无限循环
     * 第一层父消息，并将父消息的 parent 设为 null
     */
    private fun extractParentMessageSafely(msg: ChatMessage): SimplifiedChatMessage? {
        return if (msg.parentMessageId != null && msg.parentMessage != null) {
            val parentObj = msg.parentMessage

            // 安全检查：确保 parentObj 不为 null
            parentObj?.let { parent ->
                SimplifiedChatMessage(
                    jsonMessageId = parent.jsonMessageId,
                    timestamp = parent.timestamp,
                    message = parent.message,
                    actorDisplayName = parent.actorDisplayName,
                    actorType = parent.actorType,
                    actorId = parent.actorId,
                    messageType = parent.messageType,
                    messageParameters = if (parent.messageParameters != null) {
                        parent.messageParameters
                    } else {
                        HashMap()
                    },
                    parentMessageId = parent.parentMessageId,
                    reactions = if (parent.reactions != null) {
                        parent.reactions
                    } else {
                        LinkedHashMap()
                    },
                    isTemporary = parent.isTemporary,
                    referenceId = parent.referenceId,
                    id = parent.id,
                    expirationTimestamp = 0,
                    isReplyable = parent.replyable,
                    markdown = parent.renderMarkdown,
                    systemMessage = parent.getSystemMessage(),
                    token = parent.token,
                    // parent = extractParentMessageSafely(parent)
                    parent = null
                )
            }
        } else {
            null
        }
    }

    /**
     * 逐一转发消息
     */
    private fun forwardMessagesSequentially(messageIds: Set<String>) {
        val bundle = Bundle()
        bundle.putBoolean(BundleKeys.KEY_FORWARD_MSG_FLAG, true)
        bundle.putStringArrayList(BundleKeys.KEY_FORWARD_MESSAGE_IDS, ArrayList(messageIds))
        bundle.putString(BundleKeys.KEY_FORWARD_MESSAGES_JSON, jsonString())
        bundle.putString(BundleKeys.KEY_FORWARD_HIDE_SOURCE_ROOM, roomToken)
        bundle.putBoolean(BundleKeys.KEY_FORWARD_SEQUENTIAL_MODE, true)

        val intent = Intent(chatActivity, ConversationsListActivity::class.java)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    /**
     * 合并转发消息
     */
    private fun forwardMessagesMerged(messageIds: Set<String>) {
        Log.e("Ray", "jsonString() = ${jsonString()}")
        val bundle = Bundle()
        bundle.putBoolean(BundleKeys.KEY_FORWARD_MSG_FLAG, true)
        bundle.putStringArrayList(BundleKeys.KEY_FORWARD_MESSAGE_IDS, ArrayList(messageIds))
        bundle.putString(BundleKeys.KEY_FORWARD_MESSAGES_JSON, jsonString())
        bundle.putString(BundleKeys.KEY_FORWARD_HIDE_SOURCE_ROOM, roomToken)
        bundle.putBoolean(BundleKeys.KEY_FORWARD_SEQUENTIAL_MODE, false)

        val intent = Intent(chatActivity, ConversationsListActivity::class.java)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    fun show() {
        view?.visibility = View.VISIBLE
    }

    fun hide() {
        view?.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "ChatBottomMessageMenuFragment"

        fun newInstance(): ChatBottomMessageMenuFragment {
            return ChatBottomMessageMenuFragment()
        }
    }

    data class SimplifiedMultiMessage(
        var title: String? = null,
        var message: ArrayList<SimplifiedChatMessage>? = null,
    ) {

    }
    // 在 ChatBottomMessageMenuFragment.kt 中添加内部类
    data class SimplifiedChatMessage(
        val jsonMessageId: Int,
        val timestamp: Long,
        val message: String?,
        val actorDisplayName: String?,
        val actorType: String?,
        val actorId: String?,
        val messageType: String?,
        val messageParameters: HashMap<String?, HashMap<String?, String?>>?,
        val parentMessageId: Long?,
        val reactions: LinkedHashMap<String, Int>?,
        val isTemporary: Boolean,
        val referenceId: String?,
        val id: String?,
        val expirationTimestamp: Int = 0,
        val isReplyable: Boolean = false,
        val markdown: Boolean? = null,
        val systemMessage: String? = null,
        val token: String?,
        val parent: SimplifiedChatMessage?
    )
}

