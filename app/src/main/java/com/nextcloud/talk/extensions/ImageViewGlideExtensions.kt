/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2023 Marcel Hibbe <dev@mhibbe.de>
 * SPDX-FileCopyrightText: 2022 Tim Krüger <t@timkrueger.me>
 * SPDX-FileCopyrightText: 2022 Nextcloud GmbH
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("TooManyFunctions")

package com.nextcloud.talk.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.widget.ImageView
import androidx.compose.foundation.shape.CircleShape
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.load
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.result
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.nextcloud.talk.R
import com.nextcloud.talk.chat.data.model.ChatMessage
import com.nextcloud.talk.data.user.model.User
import com.nextcloud.talk.models.domain.ConversationModel
import com.nextcloud.talk.models.json.conversations.Conversation
import com.nextcloud.talk.models.json.conversations.ConversationEnums
import com.nextcloud.talk.ui.theme.ViewThemeUtils
import com.nextcloud.talk.utils.ApiUtils
import com.nextcloud.talk.utils.DisplayUtils
import com.nextcloud.talk.utils.TextDrawable
import io.reactivex.disposables.Disposable
import java.util.Locale
import kotlin.math.min

private const val ROUNDING_PIXEL = 16f
private const val TAG = "ImageViewGlideExtensions"

@Deprecated("use other constructor that expects com.nextcloud.talk.models.domain.ConversationModel")
fun ImageView.loadConversationAvatarGlide(
    user: User,
    conversation: Conversation,
    ignoreCache: Boolean,
    viewThemeUtils: ViewThemeUtils?
) {
    loadConversationAvatarGlide(
        user,
        ConversationModel.mapToConversationModel(conversation, user),
        ignoreCache,
        viewThemeUtils
    )
}

@Suppress("ReturnCount")
fun ImageView.loadConversationAvatarGlide(
    user: User,
    conversation: ConversationModel,
    ignoreCache: Boolean,
    viewThemeUtils: ViewThemeUtils?
) {
    val imageRequestUri = ApiUtils.getUrlForConversationAvatarWithVersion(
        1,
        user.baseUrl,
        conversation.token,
        DisplayUtils.isDarkModeOn(this.context),
        conversation.avatarVersion
    )

    if (conversation.avatarVersion.isNullOrEmpty() && viewThemeUtils != null) {
        when (conversation.type) {
            ConversationEnums.ConversationType.ROOM_GROUP_CALL ->
                loadDefaultGroupCallAvatarGlide(viewThemeUtils)

            ConversationEnums.ConversationType.ROOM_PUBLIC_CALL ->
                loadDefaultPublicCallAvatarGlide(viewThemeUtils)

            else -> {}
        }
    }

    // these placeholders are only used when the request fails completely. The server also return default avatars
    // when no own images are set. (although these default avatars can not be themed for the android app..)
    val errorPlaceholder =
        when (conversation.type) {
            ConversationEnums.ConversationType.ROOM_GROUP_CALL ->
                ContextCompat.getDrawable(context, R.drawable.ic_circular_group)

            ConversationEnums.ConversationType.ROOM_PUBLIC_CALL ->
                ContextCompat.getDrawable(context, R.drawable.ic_circular_link)

            else -> ContextCompat.getDrawable(context, R.drawable.account_circle_96dp)
        }

    loadAvatarInternalGlide(user, imageRequestUri, ignoreCache, errorPlaceholder)
}

fun ImageView.loadUserAvatarGlide(
    user: User,
    avatarId: String,
    requestBigSize: Boolean = true,
    ignoreCache: Boolean
) {
    val imageRequestUri = ApiUtils.getUrlForAvatar(
        user.baseUrl!!,
        avatarId,
        requestBigSize
    )

    loadAvatarInternalGlide(user, imageRequestUri, ignoreCache, null)
}

fun ImageView.loadFederatedUserAvatarGlide(message: ChatMessage) {
    val cloudId = message.actorId!!
    val darkTheme = if (DisplayUtils.isDarkModeOn(context)) 1 else 0
    val ignoreCache = false
    val requestBigSize = true
    loadFederatedUserAvatarGlide(
        message.activeUser!!,
        message.activeUser!!.baseUrl!!,
        message.token!!,
        cloudId,
        darkTheme,
        requestBigSize,
        ignoreCache
    )
}

@Suppress("LongParameterList")
fun ImageView.loadFederatedUserAvatarGlide(
    user: User,
    baseUrl: String,
    token: String,
    cloudId: String,
    darkTheme: Int,
    requestBigSize: Boolean = true,
    ignoreCache: Boolean
) {
    val imageRequestUri = ApiUtils.getUrlForFederatedAvatar(
        baseUrl,
        token,
        cloudId,
        darkTheme,
        requestBigSize
    )
    Log.d(TAG, "federated avatar URL: $imageRequestUri")

    loadAvatarInternalGlide(user, imageRequestUri, ignoreCache, null)
}

private fun ImageView.loadAvatarInternalGlide(
    user: User?,
    url: String,
    ignoreCache: Boolean,
    errorPlaceholder: Drawable?
) {
    val cachePolicy = if (ignoreCache) {
        CachePolicy.WRITE_ONLY
    } else {
        CachePolicy.ENABLED
    }

    if (ignoreCache && this.result is SuccessResult) {
        val result = this.result as SuccessResult
        val memoryCacheKey = result.memoryCacheKey
        val memoryCache = context.imageLoader.memoryCache
        memoryCacheKey?.let { memoryCache?.remove(it) }

        val diskCacheKey = result.diskCacheKey
        val diskCache = context.imageLoader.diskCache
        diskCacheKey?.let { diskCache?.remove(it) }
    }

    // DisposableWrapper(
    //     load(url) {
    //         user?.let {
    //             addHeader(
    //                 "Authorization",
    //                 ApiUtils.getCredentials(user.username, user.token)!!
    //             )
    //         }
    //         transformations(CircleCropTransformation())
    //         error(errorPlaceholder ?: ContextCompat.getDrawable(context, R.drawable.account_circle_96dp))
    //         fallback(errorPlaceholder ?: ContextCompat.getDrawable(context, R.drawable.account_circle_96dp))
    //         listener(onError = { _, result ->
    //             Log.w(TAG, "Can't load avatar with URL: $url", result.throwable)
    //         })
    //         memoryCachePolicy(cachePolicy)
    //         diskCachePolicy(cachePolicy)
    //     }
    // )
    Glide.with(context)
        .load(url)
        // .apply(RequestOptions.centerCropTransform())
        .apply(RequestOptions.bitmapTransform(RoundedCorners(180)))
        .placeholder(errorPlaceholder ?: ContextCompat.getDrawable(context, R.drawable.account_circle_96dp))
        .error(errorPlaceholder ?: ContextCompat.getDrawable(context, R.drawable.account_circle_96dp))
        .into(this)
}

@Deprecated("Use function loadAvatar", level = DeprecationLevel.WARNING)
fun ImageView.loadAvatarWithUrlGlide(user: User? = null, url: String) {
    loadAvatarInternalGlide(user, url, false, null)
}

fun ImageView.loadPhoneAvatarGlide(viewThemeUtils: ViewThemeUtils) {
    val drawable = viewThemeUtils.talk.themePlaceholderAvatar(this, R.drawable.ic_phone_small)
    loadUserAvatarGlide(drawable)
}

fun ImageView.loadThumbnailGlide(url: String, user: User) {
    val requestBuilder = ImageRequest.Builder(context)
        .data(url)
        .crossfade(true)
        .target(this)
        .transformations(CircleCropTransformation())

    val layers = arrayOfNulls<Drawable>(2)
    layers[0] = ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)
    layers[1] = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
    requestBuilder.placeholder(LayerDrawable(layers))

    if (url.startsWith(user.baseUrl!!) &&
        (url.contains("index.php/core/preview") || url.contains("/avatar/"))
    ) {
        requestBuilder.addHeader(
            "Authorization",
            ApiUtils.getCredentials(user.username, user.token)!!
        )
    }

    // return DisposableWrapper(context.imageLoader.enqueue(requestBuilder.build()))
    Glide.with(context)
        .load(url)
        // .apply(RequestOptions.centerCropTransform())
        .apply(RequestOptions.bitmapTransform(RoundedCorners(180)))
        .into(this)
}

fun ImageView.loadImageGlide(url: String, user: User, placeholder: Drawable? = null) {
    var finalPlaceholder = placeholder
    if (finalPlaceholder == null) {
        finalPlaceholder = ContextCompat.getDrawable(context!!, R.drawable.ic_mimetype_file)
    }

    val requestBuilder = ImageRequest.Builder(context)
        .data(url)
        .crossfade(true)
        .target(this)
        .placeholder(finalPlaceholder)
        .error(finalPlaceholder)
        .transformations(RoundedCornersTransformation(ROUNDING_PIXEL, ROUNDING_PIXEL, ROUNDING_PIXEL, ROUNDING_PIXEL))

    if (url.startsWith(user.baseUrl!!) &&
        (url.contains("index.php/core/preview") || url.contains("/avatar/"))
    ) {
        requestBuilder.addHeader(
            "Authorization",
            ApiUtils.getCredentials(user.username, user.token)!!
        )
    }

    // return DisposableWrapper(context.imageLoader.enqueue(requestBuilder.build()))
    Glide.with(context)
        .load(url)
        .apply(RequestOptions.bitmapTransform(RoundedCorners(180)))
        .placeholder(finalPlaceholder)
        .error(finalPlaceholder)
        .into(this)
}

fun ImageView.loadAvatarOrImagePreviewGlide(
    url: String,
    user: User,
    placeholder: Drawable? = null
) {
    if (url.contains("/avatar/")) {
        loadAvatarInternalGlide(user, url, false, null)
    } else {
        loadImageGlide(url, user, placeholder)
    }
}

fun ImageView.loadUserAvatarGlide(any: Any?) {
    // DisposableWrapper(
    //     load(any) {
    //         transformations(CircleCropTransformation())
    //     }
    // )
    load(any)
}

fun ImageView.loadSystemAvatarGlide() {
    val layers = arrayOfNulls<Drawable>(2)
    layers[0] = ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)
    layers[1] = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
    val layerDrawable = LayerDrawable(layers)
    val data: Any = layerDrawable

    // return DisposableWrapper(
    //     load(data) {
    //         transformations(CircleCropTransformation())
    //     }
    // )
    load(data)
}

fun ImageView.loadNoteToSelfAvatarGlide() {
    val layers = arrayOfNulls<Drawable>(2)
    layers[0] = ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)
    layers[1] = ContextCompat.getDrawable(context, R.drawable.ic_note_to_self)
    val layerDrawable = LayerDrawable(layers)
    setImageDrawable(CircularDrawableGlide(layerDrawable))
}

fun ImageView.loadFirstLetterAvatarGlide(name: String) {
    val layers = arrayOfNulls<Drawable>(2)
    layers[0] = ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)
    layers[1] = createTextDrawableGlide(context, name.trimStart().uppercase(Locale.ROOT))

    val layerDrawable = LayerDrawable(layers)
    val data: Any = layerDrawable

    // return DisposableWrapper(
    //     load(data) {
    //         transformations(CircleCropTransformation())
    //     }
    // )
    load(data)
}

fun ImageView.loadChangelogBotAvatarGlide() {
    loadSystemAvatar()
}

fun ImageView.loadBotsAvatarGlide() {
    val layers = arrayOfNulls<Drawable>(2)
    layers[0] = context.getColor(R.color.black).toDrawable()
    layers[1] = TextDrawable(context, ">")
    val layerDrawable = LayerDrawable(layers)
    val data: Any = layerDrawable

    // return DisposableWrapper(
    //     load(data) {
    //         transformations(CircleCropTransformation())
    //     }
    // )
    load(data)
}

fun ImageView.loadDefaultGroupCallAvatarGlide(viewThemeUtils: ViewThemeUtils) {
    val data: Any = viewThemeUtils.talk.themePlaceholderAvatar(this, R.drawable.ic_avatar_group_small) as Any
    loadUserAvatarGlide(data)
}

fun ImageView.loadTeamAvatarGlide(viewThemeUtils: ViewThemeUtils) {
    val data: Any = viewThemeUtils.talk.themePlaceholderAvatar(this, R.drawable.ic_avatar_team_small) as Any
    loadUserAvatarGlide(data)
}

fun ImageView.loadDefaultAvatarGlide(viewThemeUtils: ViewThemeUtils) {
    val data: Any = viewThemeUtils.talk.themePlaceholderAvatar(this, R.drawable.account_circle_96dp) as Any
    loadUserAvatarGlide(data)
}

fun ImageView.loadDefaultPublicCallAvatarGlide(viewThemeUtils: ViewThemeUtils) {
    val data: Any = viewThemeUtils.talk.themePlaceholderAvatar(this, R.drawable.ic_avatar_link) as Any
    loadUserAvatarGlide(data)
}

fun ImageView.loadMailAvatarGlide(viewThemeUtils: ViewThemeUtils) {
    val data: Any = viewThemeUtils.talk.themePlaceholderAvatar(this, R.drawable.ic_avatar_mail) as Any
    loadUserAvatarGlide(data)
}

fun ImageView.loadGuestAvatarGlide(user: User, name: String, big: Boolean) {
    loadGuestAvatarGlide(user.baseUrl!!, name, big)
}

fun ImageView.loadGuestAvatarGlide(baseUrl: String, name: String, big: Boolean) {
    val imageRequestUri = ApiUtils.getUrlForGuestAvatar(
        baseUrl,
        name,
        big
    )
    // return DisposableWrapper(
    //     load(imageRequestUri) {
    //         transformations(CircleCropTransformation())
    //         listener(onError = { _, result ->
    //             Log.w(TAG, "Can't load guest avatar with URL: $imageRequestUri", result.throwable)
    //         })
    //     }
    // )
    load(imageRequestUri)
}

@Suppress("MagicNumber")
private fun createTextDrawableGlide(context: Context, letter: String): Drawable {
    val size = 100
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)

    val paint = Paint().apply {
        color = ResourcesCompat.getColor(context.resources, R.color.grey_600, null)
        style = Paint.Style.FILL
    }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

    val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = size / 2f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    val xPos = size / 2f
    val yPos = (canvas.height / 2 - (textPaint.descent() + textPaint.ascent()) / 2)
    canvas.drawText(letter.take(1), xPos, yPos, textPaint)

    return bitmap.toDrawable(context.resources)
}

private class CircularDrawableGlide(private val sourceDrawable: Drawable) : Drawable() {

    private val bitmap: Bitmap = drawableToBitmap(sourceDrawable)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private val rect = RectF()
    private var radius = 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)

        radius = min(rect.width() / 2.0f, rect.height() / 2.0f)

        val matrix = Matrix()
        val scale: Float
        var dx = 0f
        var dy = 0f

        if (bitmap.width * rect.height() > rect.width() * bitmap.height) {
            // Taller than wide, scale to height and center horizontally
            scale = rect.height() / bitmap.height.toFloat()
            dx = (rect.width() - bitmap.width * scale) / 2.0f
        } else {
            // Wider than tall, scale to width and center vertically
            scale = rect.width() / bitmap.width.toFloat()
            dy = (rect.height() - bitmap.height * scale) / 2.0f
        }

        matrix.setScale(scale, scale)
        matrix.postTranslate(dx.toInt().toFloat() + rect.left, dy.toInt().toFloat() + rect.top)
        paint.shader.setLocalMatrix(matrix)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawCircle(rect.centerX(), rect.centerY(), radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated(
        "This method is no longer used in graphics optimizations",
        ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat")
    )
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = sourceDrawable.intrinsicWidth

    override fun getIntrinsicHeight(): Int = sourceDrawable.intrinsicHeight

    companion object {

        private fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable) {
                if (drawable.bitmap != null) {
                    return drawable.bitmap
                }
            }

            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1
            return drawable.toBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
