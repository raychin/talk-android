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
import android.content.Intent
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.net.toUri
import androidx.core.text.toSpanned
import androidx.recyclerview.widget.RecyclerView
import com.nextcloud.talk.R
import com.nextcloud.talk.adapters.items.FlexibleItemViewType
import com.nextcloud.talk.adapters.items.GenericTextHeaderItem
import com.nextcloud.talk.adapters.messages.PreviewMessageViewHolder.Companion.KEY_MIMETYPE
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.databinding.RvItemMultiMessageBinding
import com.nextcloud.talk.extensions.loadAvatarOrImagePreview
import com.nextcloud.talk.ui.theme.ViewThemeUtils
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.DrawableUtils.getDrawableResourceIdForMimeType
import com.nextcloud.talk.utils.FileViewerUtils
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
        var binding: RvItemMultiMessageBinding = RvItemMultiMessageBinding.bind(view)
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

        // 确保 activeUser 已设置，这对 mention 处理至关重要
        if (messageEntry.activeUser == null) {
            messageEntry.activeUser = currentUser
            Log.d("RAY", "Set activeUser to currentUser")
        }

        val messageUtils = MessageUtils(context)
        var processedMessageText = messageUtils.enrichChatMessageText(
            holder.binding.messageExcerpt.context,
            messageEntry,
            true,
            viewThemeUtils
        )

        if (processedMessageText == null) {
            processedMessageText = "".toSpanned()
        }

        holder.binding.conversationTitle.text = messageEntry.actorDisplayName

        // 判断是否是连续消息（同一个发送人）
        val isConsecutiveMessage = isConsecutiveSameSender(adapter, position)

        // 如果是连续消息且不是第一条，隐藏头像和发送者名称
        if (isConsecutiveMessage) {
            holder.binding.thumbnail.visibility = View.INVISIBLE
        } else {
            holder.binding.thumbnail.visibility = View.VISIBLE

            messageEntry.actorId?.let {
                val url = ApiUtils.getUrlForAvatar(currentUser.baseUrl, it, false)
                holder.binding.thumbnail.loadAvatarOrImagePreview(url, currentUser, null)
            }

            // ChatMessageUtils().setAvatarOnMessage(holder.binding.thumbnail, messageEntry, viewThemeUtils)
        }

        // // // val thumbnailURL = if (TextUtils.isEmpty(messageEntry.thumbnailURL)) {
        // // //     null
        // // // } else {
        // // //     generateImageUrl(messageEntry.thumbnailURL!!)
        // // // }
        // // // thumbnailURL?.let { holder.binding.thumbnail.loadThumbnail(it, currentUser) }

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

            val fileParams = messageEntry.messageParameters?.get("file")
            if (fileParams != null) {
                val fileId = fileParams["id"]
                val fileName = fileParams["name"]
                val fileSize = fileParams["size"]
                val mimetype = fileParams["mimetype"]

                Log.d("Ray", "File params: id=$fileId, name=$fileName, size=$fileSize, mimetype=$mimetype")

                // 确保 activeUser 已设置（参考 PreviewMessageViewHolder）
                if (messageEntry.activeUser == null) {
                    messageEntry.activeUser = currentUser
                    Log.d("Ray", "Set activeUser to currentUser")
                }

                // 参照 PreviewMessageViewHolder 的加载方式
                if (!fileId.isNullOrEmpty() &&
                    messageEntry.activeUser != null &&
                    messageEntry.activeUser!!.baseUrl != null
                ) {
                    // 使用 ChatMessage.getImageUrl() 相同的逻辑拼接预览 URL
                    val previewUrl = ApiUtils.getUrlForFilePreviewWithFileId(
                        messageEntry.activeUser!!.baseUrl!!,
                        fileId,
                        context.resources.getDimensionPixelSize(R.dimen.maximum_file_preview_size)
                    )

                    Log.d("Ray", "Generated preview URL: $previewUrl")
                    Log.d("Ray", messageEntry.imageUrl!!)

                    // 显示并加载图片
                    holder.binding.thumbnailImg.visibility = View.VISIBLE

                    val mimetype = messageEntry.selectedIndividualHashMap!![KEY_MIMETYPE]
                    val drawableResourceId = getDrawableResourceIdForMimeType(mimetype)
                    val placeholderDrawable = androidx.core.content.ContextCompat.getDrawable(context, drawableResourceId)
                    // 使用 loadImage 扩展方法（会自动添加认证头）
                    holder.binding.thumbnailImg.loadAvatarOrImagePreview(messageEntry.imageUrl!!, currentUser, placeholderDrawable)
                    holder.binding.thumbnailImg.setOnClickListener { view ->
                        handleImageOrFileClickLikeChat(messageEntry, holder.binding.progressBar)
                    }
                } else {
                    Log.e("Ray", "Cannot load image: fileId or baseUrl is null!")
                    holder.binding.thumbnailImg.visibility = View.GONE
                }
            }

            // val previewUrlFromPath = ApiUtils.getUrlForFilePreviewWithRemotePath(
            //     currentUser.baseUrl!!,
            //     messageEntry.messageParameters!!["file"]!!["id"]!!,
            //     context.resources.getDimensionPixelSize(R.dimen.maximum_file_preview_size)
            // )
            // holder.binding.thumbnailImg.loadImage(previewUrlFromPath,
            //     currentUser,
            //     null)
            // // messageEntry.imageUrl?.let {
            // //     holder.binding.thumbnailImg.visibility = View.VISIBLE
            // //     holder.binding.thumbnailImg.load(it) {
            // //         addHeader(
            // //             "Authorization",
            // //             ApiUtils.getCredentials(messageEntry.activeUser!!.username, messageEntry.activeUser!!.token)!!
            // //         )
            // //     }
            // // } ?: run {
            // //     holder.binding.thumbnailImg.visibility = View.GONE
            // // }
            holder.binding.messageExcerpt.visibility = View.VISIBLE
            holder.binding.messageExcerpt.text = messageEntry.messageParameters?.get("file")?.get("name")
            holder.binding.thumbnailSize.visibility = View.VISIBLE
            holder.binding.thumbnailSize.text = formatFileSize(messageEntry.messageParameters?.get("file")?.get
                    ("size")!!)
        } else {
            holder.binding.messageExcerpt.visibility = View.VISIBLE
            val layoutParams = holder.binding.messageExcerpt.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            layoutParams.marginStart = (48 * context.resources.displayMetrics.density).toInt()
            holder.binding.messageExcerpt.layoutParams = layoutParams
            // holder.binding.messageExcerpt.text = messageEntry.message

            processedMessageText = messageUtils.processMessageParameters(
                holder.binding.messageExcerpt.context,
                viewThemeUtils,
                processedMessageText!!.toSpanned(),
                messageEntry,
                holder.itemView
            )
            Log.e("Ray", "multi || $processedMessageText")
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

    fun generateImageUrl(url: String): String {
        if (url.isEmpty()) {
            return ""
        }
        if (!url.endsWith(".png") && !url.endsWith(".jpg") && !url.endsWith(".jpeg")) {
            return "${url}.png"
        }
        return url
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

    /**
     * 判断当前消息是否与前一条消息来自同一个发送人
     */
    private fun isConsecutiveSameSender(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        position: Int
    ): Boolean {
        if (position <= 0) return false

        val currentItem = adapter.getItem(position)
        val previousItem = adapter.getItem(position - 1)

        if (currentItem !is MessageMultiItem || previousItem !is MessageMultiItem) {
            return false
        }

        val currentActorId = currentItem.messageEntry.actorId
        val previousActorId = previousItem.messageEntry.actorId

        // 如果发送人 ID 相同，认为是连续消息
        return currentActorId != null && currentActorId == previousActorId
    }

    // ... ray add code ...
    private fun handleImageOrFileClickLikeChat(message: ChatMessage, progressBar: ProgressBar) {
        message.activeUser = currentUser

        when {
            // 如果是附件消息（文件、图片等），使用 FileViewerUtils 打开
            message.getCalculateMessageType() === ChatMessage.MessageType.SINGLE_NC_ATTACHMENT_MESSAGE -> {
                val fileViewerUtils = FileViewerUtils(context, message.activeUser!!)

                if (message.activeUser != null &&
                    message.activeUser!!.username != null &&
                    message.activeUser!!.baseUrl != null
                ) {
                    // 创建 ProgressUi 对象（由于是 Compose UI，这里传入 null）
                    val progressUi = FileViewerUtils.ProgressUi(
                        progressBar = progressBar,
                        messageText = null,
                        previewImage = ImageView(context)
                    )

                    fileViewerUtils.openFile(message, progressUi)
                }
            }
            // 如果是普通图片链接消息
            message.messageType == ChatMessage.MessageType.SINGLE_LINK_IMAGE_MESSAGE.name -> {
                message.imageUrl?.let { url ->
                    val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(browserIntent)
                }
            }
            // 其他类型的消息，尝试直接打开图片
            message.imageUrl != null -> {
                val browserIntent = Intent(Intent.ACTION_VIEW, message.imageUrl!!.toUri())
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(browserIntent)
            }
        }
    }
    // ... ray add code ...
}
