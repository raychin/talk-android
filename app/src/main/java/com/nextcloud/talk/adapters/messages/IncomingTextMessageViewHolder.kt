/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2021-2023 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2021 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2021 Tim Krüger <t@timkrueger.me>
 * SPDX-FileCopyrightText: 2017-2018 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.adapters.messages

import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.CheckBox
import androidx.core.content.ContextCompat
import androidx.core.text.toSpanned
import autodagger.AutoInjector
import coil.load
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.nextcloud.android.common.ui.theme.utils.ColorRole
import com.nextcloud.talk.R
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.application.NextcloudTalkApplication.Companion.sharedApplication
import com.nextcloud.talk.chat.ChatActivity
import com.nextcloud.talk.chat.clps.MultiMessageDetailActivity
import com.nextcloud.talk.chat.data.ChatMessageRepository
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.chat.data.model.clps.MultiMessage
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.ItemCustomIncomingTextMessageBinding
import com.nextcloud.talk.ui.theme.ViewThemeUtils
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.CapabilitiesUtil.hasSpreedFeatureCapability
import com.nextcloud.talk.utils.ChatMessageUtils
import com.nextcloud.talk.utils.DateUtils
import com.nextcloud.talk.utils.SpreedFeatures
import com.nextcloud.talk.utils.TextMatchers
import com.nextcloud.talk.utils.database.user.CurrentUserProviderNew
import com.nextcloud.talk.utils.message.MessageUtils
import com.nextcloud.talk.utils.preferences.AppPreferences
import com.stfalcon.chatkit.messages.MessageHolders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject

@AutoInjector(NextcloudTalkApplication::class)
class IncomingTextMessageViewHolder(itemView: View, payload: Any) :
    MessageHolders.IncomingTextMessageViewHolder<ChatMessage>(itemView, payload) {

    private val binding: ItemCustomIncomingTextMessageBinding = ItemCustomIncomingTextMessageBinding.bind(itemView)

    @Inject
    lateinit var context: Context

    @Inject
    lateinit var viewThemeUtils: ViewThemeUtils

    @Inject
    lateinit var messageUtils: MessageUtils

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var dateUtils: DateUtils

    @Inject
    lateinit var currentUserProvider: CurrentUserProviderNew

    lateinit var commonMessageInterface: CommonMessageInterface

    @Inject
    lateinit var chatRepository: ChatMessageRepository

    private var job: Job? = null

    override fun onBind(message: ChatMessage) {
        super.onBind(message)
        sharedApplication!!.componentApplication.inject(this)
        setAvatarAndAuthorOnMessageItem(message)
        colorizeMessageBubble(message)
        itemView.isSelected = false
        val user = currentUserProvider.currentUser.blockingGet()
        val hasCheckboxes = processCheckboxes(
            message,
            user
        )
        processMessage(message, hasCheckboxes)
    }

    /**
     * CheckBox逻辑处理
     * TODO RAY 抽象到MessageHolders，公用
     */
    private fun initMessageCheckbox(message: ChatMessage) {
        val chatActivity = commonMessageInterface as ChatActivity
        val adapter = chatActivity.adapter
        if (adapter is TalkMessagesListAdapter<*>) {
            val isInSelectionMode = adapter.isSelectionMode
            // val isSelected = adapter.isMessageSelected(message.id.toString())

            // 根据选择模式显示或隐藏复选框
            binding.messageCheckbox.visibility = if (isInSelectionMode) {
                View.VISIBLE
            } else {
                View.GONE
            }

            binding.root.setOnClickListener {
                if (!isInSelectionMode) {
                    return@setOnClickListener
                }
                commonMessageInterface.onSelectMessage(message)

                // 获取消息ID并检查选中状态，然后设置checkbox为相应状态
                val messageId = message.jsonMessageId.toString()
                val isSelected = adapter.isMessageSelected(messageId)
                binding.messageCheckbox.isChecked = isSelected

                // 根据选择状态调整透明度
                val alpha = if (isSelected) 0.6f else 1.0f
                itemView.setAlpha(alpha)
            }
        }
    }
    private fun processMessage(message: ChatMessage, hasCheckboxes: Boolean) {
        initMessageCheckbox(message)
        var textSize = context.resources!!.getDimension(R.dimen.chat_text_size)
        if (!hasCheckboxes) {
            binding.messageText.visibility = View.VISIBLE
            binding.checkboxContainer.visibility = View.GONE
            var processedMessageText = messageUtils.enrichChatMessageText(
                binding.messageText.context,
                message,
                true,
                viewThemeUtils
            )

            val spansFromString: Array<Any> = processedMessageText!!.getSpans(
                0,
                processedMessageText.length,
                Any::class.java
            )

            if (spansFromString.isNotEmpty()) {
                binding.bubble.layoutParams.apply {
                    width = FlexboxLayout.LayoutParams.MATCH_PARENT
                }
                binding.messageText.layoutParams.apply {
                    width = FlexboxLayout.LayoutParams.MATCH_PARENT
                }
            } else {
                binding.bubble.layoutParams.apply {
                    width = FlexboxLayout.LayoutParams.WRAP_CONTENT
                }
                binding.messageText.layoutParams.apply {
                    width = FlexboxLayout.LayoutParams.WRAP_CONTENT
                }
            }

            /**
             * TODO RAY 判断 message.message 是否可以转为MultiMessage消息
             */

            /**
             * 判断 message.message 是否可以转为 MultiMessage 消息
             * 如果 message.message 是 JSON 格式且包含 title 和 message 数组字段，则尝试解析为 MultiMessage
             */
            if (canParseAsMultiMessage(message.message)) {
                processedMessageText = parseAndDisplayMultiMessage(message).toSpanned()
                // 为 MultiMessage 文本添加点击跳转功能
                val multiMessage = Gson().fromJson(message.message, MultiMessage::class.java)
                addClickToNavigateToMultiMessageDetail(processedMessageText, message, multiMessage)
            } else {
                // 原始逻辑：处理普通消息
                processedMessageText = messageUtils.processMessageParameters(
                    binding.messageText.context,
                    viewThemeUtils,
                    processedMessageText,
                    message,
                    itemView
                )
            }
            // processedMessageText = messageUtils.processMessageParameters(
            //     binding.messageText.context,
            //     viewThemeUtils,
            //     processedMessageText,
            //     message,
            //     itemView
            // )

            val messageParameters = message.messageParameters
            if (
                (messageParameters == null || messageParameters.size <= 0) &&
                TextMatchers.isMessageWithSingleEmoticonOnly(message.text)
            ) {
                textSize = (textSize * TEXT_SIZE_MULTIPLIER).toFloat()
                itemView.isSelected = true
                binding.messageAuthor.visibility = View.GONE
            }
            binding.messageText.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            binding.messageText.text = processedMessageText
            // just for debugging:
            // binding.messageText.text =
            //     SpannableStringBuilder(processedMessageText).append(" (" + message.jsonMessageId + ")")
        } else {
            binding.messageText.visibility = View.GONE
            binding.checkboxContainer.visibility = View.VISIBLE
        }

        if (message.lastEditTimestamp != 0L && !message.isDeleted) {
            binding.messageEditIndicator.visibility = View.VISIBLE
            binding.messageTime.text = dateUtils.getLocalTimeStringFromTimestamp(message.lastEditTimestamp!!)
        } else {
            binding.messageEditIndicator.visibility = View.GONE
            binding.messageTime.text = dateUtils.getLocalTimeStringFromTimestamp(message.timestamp)
        }
        viewThemeUtils.platform.colorTextView(binding.messageTime, ColorRole.ON_SURFACE_VARIANT)

        // parent message handling
        val chatActivity = commonMessageInterface as ChatActivity
        binding.messageQuote.quotedChatMessageView.visibility =
            if (!message.isDeleted &&
                message.parentMessageId != null &&
                message.parentMessageId != chatActivity.conversationThreadId
            ) {
                processParentMessage(message)
                View.VISIBLE
            } else {
                View.GONE
            }

        binding.messageQuote.quotedChatMessageView.setOnLongClickListener { l: View? ->
            commonMessageInterface.onOpenMessageActionsDialog(message)
            true
        }

        itemView.setTag(R.string.replyable_message_view_tag, message.replyable)

        Thread().showThreadPreview(
            chatActivity,
            message,
            threadBinding = binding.threadTitleWrapper,
            reactionsBinding = binding.reactions,
            openThread = { openThread(message) }
        )

        Reaction().showReactions(
            message,
            ::clickOnReaction,
            ::longClickOnReaction,
            binding.reactions,
            binding.messageText.context,
            false,
            viewThemeUtils
        )
    }

    // ... existing code ...
    /**
     * 判断消息内容是否可以解析为 MultiMessage
     *
     * @param messageContent 消息内容字符串
     * @return 如果可以解析为 MultiMessage 返回 true，否则返回 false
     */
    private fun canParseAsMultiMessage(messageContent: String?): Boolean {
        if (messageContent.isNullOrBlank()) {
            return false
        }

        // 快速检查：JSON 对象应该以 { 开始
        val trimmedContent = messageContent.trim()
        if (!trimmedContent.startsWith("{")) {
            return false
        }

        return try {
            // 尝试使用 Gson 解析为 MultiMessage
            val gson = Gson()
            val multiMessage = gson.fromJson(trimmedContent, MultiMessage::class.java)

            // 验证解析结果是否有效
            multiMessage != null &&
                (multiMessage.title != null || !multiMessage.message.isNullOrEmpty())
        } catch (e: JsonSyntaxException) {
            // 解析失败，说明不是有效的 JSON 格式
            Log.d(TAG, "Message cannot be parsed as MultiMessage: ${e.message}")
            false
        } catch (e: Exception) {
            // 其他异常也视为不可解析
            Log.d(TAG, "Error parsing message as MultiMessage: ${e.message}")
            false
        }
    }

    /**
     * 解析并显示 MultiMessage 消息
     *
     * @param chatMessage 聊天消息对象
     * @return 处理后的消息文本
     */
    private fun parseAndDisplayMultiMessage(chatMessage: ChatMessage): CharSequence {
        return try {
            val gson = Gson()
            val multiMessage = gson.fromJson(chatMessage.message, MultiMessage::class.java)

            // 构建 MultiMessage 的显示文本
            val displayText = StringBuilder()

            // 添加标题（如果有）
            if (!multiMessage.title.isNullOrBlank()) {
                displayText.append(multiMessage.title)
                displayText.append("\n\n")
            }

            // 添加消息数量提示
            val messageCount = multiMessage.message?.size ?: 0
            if (messageCount > 0) {
                displayText.append("【包含 $messageCount 条消息】\n")

                // 显示前几条消息的预览
                val previewLimit = minOf(messageCount, MAX_MULTI_MESSAGE)
                for (i in 0 until previewLimit) {
                    val msg = multiMessage.message!![i]
                    val actorName = msg.actorDisplayName ?: "未知"
                    val msgText = msg.message?.take(50) ?: ""

                    displayText.append("- $actorName: $msgText")
                    if (msg.message?.length ?: 0 > 50) {
                        displayText.append("...")
                    }
                    displayText.append("\n")
                }

                // 如果消息超过 MAX_MULTI_MESSAGE 条，显示省略提示
                if (messageCount > MAX_MULTI_MESSAGE) {
                    displayText.append("… 还有 ${messageCount - MAX_MULTI_MESSAGE} 条消息")
                }
            }

            // 使用工具类处理消息参数（如果需要）
            messageUtils.processMessageParameters(
                binding.messageText.context,
                viewThemeUtils,
                displayText.toSpanned(),
                chatMessage,
                itemView
            )

        } catch (e: Exception) {
            // 如果解析失败，回退到原始消息处理
            Log.e(TAG, "Failed to parse MultiMessage", e)
            messageUtils.processMessageParameters(
                binding.messageText.context,
                viewThemeUtils,
                chatMessage.message!!.toSpanned(),
                chatMessage,
                itemView
            )
        }
    }

    /**
     * 为 MultiMessage 文本添加点击事件，跳转到消息详情页面
     *
     * @param text 已处理的文本
     * @param chatMessage 当前聊天消息
     * @param multiMessage 解析后的多消息对象
     * @return 带点击事件的文本
     */
    private fun addClickToNavigateToMultiMessageDetail(
        text: CharSequence,
        chatMessage: ChatMessage,
        multiMessage: MultiMessage
    ): CharSequence {
        // 将整个消息项设置为可点击，跳转到消息列表查看完整内容
        binding.messageText.setOnClickListener {
            navigateToMultiMessageDetail(chatMessage, multiMessage)
        }

        return text
    }

    /**
     * 跳转到 MultiMessage 详情页面，显示完整的消息列表
     *
     * @param chatMessage 当前聊天消息
     * @param multiMessage 多消息对象
     */
    private fun navigateToMultiMessageDetail(chatMessage: ChatMessage, multiMessage: MultiMessage) {
        try {
            val chatActivity = commonMessageInterface as? ChatActivity
            if (chatActivity == null) {
                Log.w(TAG, "commonMessageInterface is not a ChatActivity, cannot navigate")
                return
            }

            // 创建 Intent 跳转到消息详情页面
            // TODO RAY: 需要创建 MultiMessageDetailActivity 来显示完整的消息列表
            val intent = Intent(binding.messageText.context, MultiMessageDetailActivity::class.java).apply {
                putExtra(KEY_ROOM_TOKEN, chatActivity.roomToken)
                putExtra(KEY_MESSAGE_ID, chatMessage.jsonMessageId)
                // putParcelableArrayListExtra(KEY_MULTI_MESSAGE_JSON, multiMessage.message as ArrayList<out Parcelable?>?)
                putExtra(KEY_MULTI_MESSAGE_JSON, chatMessage.message)
                putExtra(KEY_TITLE, multiMessage.title ?: "")
                putExtra(KEY_MESSAGE_COUNT, multiMessage.message?.size ?: 0)
            }

            binding.messageText.context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to MultiMessage detail", e)
            Snackbar.make(
                binding.root,
                R.string.nc_common_error_sorry,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    // ... existing code ...

    private fun processCheckboxes(chatMessage: ChatMessage, user: User): Boolean {
        val chatActivity = commonMessageInterface as ChatActivity
        val message = chatMessage.message!!.toSpanned()
        val messageTextView = binding.messageText
        val checkBoxContainer = binding.checkboxContainer
        val isOlderThanTwentyFourHours = chatMessage
            .createdAt
            .before(Date(System.currentTimeMillis() - AGE_THRESHOLD_FOR_EDIT_MESSAGE))

        val messageIsEditable = hasSpreedFeatureCapability(
            user.capabilities?.spreedCapability!!,
            SpreedFeatures.EDIT_MESSAGES
        ) &&
            !isOlderThanTwentyFourHours

        checkBoxContainer.removeAllViews()
        val regex = """(- \[(X|x| )])\s*(.+)""".toRegex(RegexOption.MULTILINE)
        val matches = regex.findAll(message)

        if (matches.none()) return false

        val firstPart = message.toString().substringBefore("\n- [")
        messageTextView.text = messageUtils.enrichChatMessageText(
            binding.messageText.context,
            firstPart,
            true,
            viewThemeUtils
        )

        val checkboxList = mutableListOf<CheckBox>()

        matches.forEach { matchResult ->
            val isChecked = matchResult.groupValues[CHECKED_GROUP_INDEX] == "X" ||
                matchResult.groupValues[CHECKED_GROUP_INDEX] == "x"
            val taskText = matchResult.groupValues[TASK_TEXT_GROUP_INDEX].trim()

            val checkBox = CheckBox(checkBoxContainer.context).apply {
                text = taskText
                this.isChecked = isChecked
                this.isEnabled = (
                    chatMessage.actorType == "bots" ||
                        chatActivity.userAllowedByPrivilages(chatMessage)
                    ) &&
                    messageIsEditable

                setTextColor(ContextCompat.getColor(context, R.color.no_emphasis_text))

                setOnCheckedChangeListener { _, _ ->
                    updateCheckboxStates(chatMessage, user, checkboxList)
                }
            }
            checkBoxContainer.addView(checkBox)
            checkboxList.add(checkBox)
            viewThemeUtils.platform.themeCheckbox(checkBox)
        }
        return true
    }

    private fun updateCheckboxStates(chatMessage: ChatMessage, user: User, checkboxes: List<CheckBox>) {
        job = CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                val apiVersion: Int = ApiUtils.getChatApiVersion(
                    user.capabilities?.spreedCapability!!,
                    intArrayOf(1)
                )
                val updatedMessage = updateMessageWithCheckboxStates(chatMessage.message!!, checkboxes)
                chatRepository.editChatMessage(
                    user.getCredentials(),
                    ApiUtils.getUrlForChatMessage(apiVersion, user.baseUrl!!, chatMessage.token!!, chatMessage.id),
                    updatedMessage
                ).collect { result ->
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) {
                            val editedMessage = result.getOrNull()?.ocs?.data!!.parentMessage!!
                            Log.d(TAG, "EditedMessage: $editedMessage")
                            binding.messageEditIndicator.apply {
                                visibility = View.VISIBLE
                            }
                            binding.messageTime.text =
                                dateUtils.getLocalTimeStringFromTimestamp(editedMessage.lastEditTimestamp!!)
                        } else {
                            Snackbar.make(binding.root, R.string.nc_common_error_sorry, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun updateMessageWithCheckboxStates(originalMessage: String, checkboxes: List<CheckBox>): String {
        var updatedMessage = originalMessage
        val regex = """(- \[(X|x| )])\s*(.+)""".toRegex(RegexOption.MULTILINE)

        checkboxes.forEach { _ ->
            updatedMessage = regex.replace(updatedMessage) { matchResult ->
                val taskText = matchResult.groupValues[TASK_TEXT_GROUP_INDEX].trim()
                val checkboxState = if (checkboxes.find { it.text == taskText }?.isChecked == true) "X" else " "
                "- [$checkboxState] $taskText"
            }
        }
        return updatedMessage
    }

    private fun longClickOnReaction(chatMessage: ChatMessage) {
        commonMessageInterface.onLongClickReactions(chatMessage)
    }

    private fun clickOnReaction(chatMessage: ChatMessage, emoji: String) {
        commonMessageInterface.onClickReaction(chatMessage, emoji)
    }

    private fun openThread(chatMessage: ChatMessage) {
        commonMessageInterface.openThread(chatMessage)
    }

    private fun setAvatarAndAuthorOnMessageItem(message: ChatMessage) {
        val actorName = message.actorDisplayName
        if (!actorName.isNullOrBlank()) {
            binding.messageAuthor.visibility = View.VISIBLE
            binding.messageAuthor.text = actorName
            binding.messageUserAvatar.setOnClickListener {
                (payload as? MessagePayload)?.profileBottomSheet?.showFor(message, itemView.context)
            }
        } else {
            binding.messageAuthor.setText(R.string.nc_nick_guest)
        }

        if (!message.isGrouped && !message.isOneToOneConversation && !message.isFormerOneToOneConversation) {
            ChatMessageUtils().setAvatarOnMessage(binding.messageUserAvatar, message, viewThemeUtils)
        } else {
            if (message.isOneToOneConversation || message.isFormerOneToOneConversation) {
                binding.messageUserAvatar.visibility = View.GONE
            } else {
                binding.messageUserAvatar.visibility = View.INVISIBLE
            }
            binding.messageAuthor.visibility = View.GONE
        }
    }

    private fun colorizeMessageBubble(message: ChatMessage) {
        viewThemeUtils.talk.themeIncomingMessageBubble(bubble, message.isGrouped, message.isDeleted)
    }

    @Suppress("Detekt.TooGenericExceptionCaught")
    private fun processParentMessage(message: ChatMessage) {
        if (message.parentMessageId != null && !message.isDeleted) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val chatActivity = commonMessageInterface as ChatActivity
                    val urlForChatting = ApiUtils.getUrlForChat(
                        chatActivity.chatApiVersion,
                        chatActivity.conversationUser?.baseUrl,
                        chatActivity.roomToken
                    )

                    val parentChatMessage = withContext(Dispatchers.IO) {
                        chatActivity.chatViewModel.getMessageById(
                            urlForChatting,
                            chatActivity.currentConversation!!,
                            message.parentMessageId!!
                        ).first()
                    }

                    parentChatMessage.activeUser = message.activeUser
                    parentChatMessage.imageUrl?.let {
                        binding.messageQuote.quotedMessageImage.visibility = View.VISIBLE
                        binding.messageQuote.quotedMessageImage.load(it) {
                            addHeader(
                                "Authorization",
                                ApiUtils.getCredentials(message.activeUser!!.username, message.activeUser!!.token)!!
                            )
                        }
                    } ?: run {
                        binding.messageQuote.quotedMessageImage.visibility = View.GONE
                    }
                    binding.messageQuote.quotedMessageAuthor.text =
                        if (parentChatMessage.actorDisplayName.isNullOrEmpty()) {
                            context.getText(R.string.nc_nick_guest)
                        } else {
                            parentChatMessage.actorDisplayName
                        }

                    binding.messageQuote.quotedMessage.text = messageUtils
                        .enrichChatReplyMessageText(
                            binding.messageQuote.quotedMessage.context,
                            parentChatMessage,
                            true,
                            viewThemeUtils
                        )

                    viewThemeUtils.talk.themeParentMessage(
                        parentChatMessage,
                        message,
                        binding.messageQuote.quotedChatMessageView,
                        R.color.high_emphasis_text
                    )

                    binding.messageQuote.quotedChatMessageView.setOnClickListener {
                        chatActivity.jumpToQuotedMessage(parentChatMessage)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error when processing parent message in view holder", e)
                }
            }
        }
    }

    fun assignCommonMessageInterface(commonMessageInterface: CommonMessageInterface) {
        this.commonMessageInterface = commonMessageInterface
    }

    override fun viewDetached() {
        super.viewDetached()
        job?.cancel()
    }

    companion object {
        const val TEXT_SIZE_MULTIPLIER = 2.5
        private val TAG = IncomingTextMessageViewHolder::class.java.simpleName
        private const val CHECKED_GROUP_INDEX = 2
        private const val TASK_TEXT_GROUP_INDEX = 3
        private const val AGE_THRESHOLD_FOR_EDIT_MESSAGE: Long = 86400000

        private const val MAX_MULTI_MESSAGE = 2
        // Intent Key 常量
        private const val KEY_ROOM_TOKEN = "room_token"
        private const val KEY_MESSAGE_ID = "message_id"
        private const val KEY_MULTI_MESSAGE_JSON = "multi_message_json"
        private const val KEY_TITLE = "title"
        private const val KEY_MESSAGE_COUNT = "message_count"
    }
}
