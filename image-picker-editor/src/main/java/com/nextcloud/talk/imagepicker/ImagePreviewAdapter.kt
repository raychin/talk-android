/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.imagepicker

import android.net.Uri
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.luck.picture.lib.entity.LocalMedia
import androidx.core.net.toUri

class ImagePreviewAdapter(
    private val imageList: List<LocalMedia>,
    private val onPhotoTapListener: ((view: View, x: Float, y: Float) -> Unit)? = null
) : RecyclerView.Adapter<ImagePreviewAdapter.ImagePreviewViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImagePreviewViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.ip_adapter_photo_view, parent, false)
        return ImagePreviewViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImagePreviewViewHolder, position: Int) {
        holder.bind(imageList[position].path)
    }

    override fun getItemCount(): Int = imageList.size

    inner class ImagePreviewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val photoView: PhotoView = itemView.findViewById(R.id.photoView)

        fun bind(imagePath: String) {
            // 启用双指缩放功能
            photoView.setScaleLevels(1f, 2f, 3f)
            photoView.setMaximumScale(5f)
            photoView.setMediumScale(3f)
            photoView.setOnScaleChangeListener { scaleFactor, focusX, focusY ->
                // 缩放回调
            }
            photoView.setOnGenericMotionListener {  v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // 禁止父容器拦截事件
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP -> {
                        // 允许父容器拦截事件
                        v.parent.requestDisallowInterceptTouchEvent(false)
                        // 判断是否是点击事件，若是则调用 performClick()
                        if (isClick(event)) {
                            v.performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }

            // 加载图片
            Glide.with(photoView.context)
                .load(imagePath.toUri())
                .into(photoView)
        }
    }
    private fun isClick(event: MotionEvent): Boolean {
        val action = event.action
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            val downX = event.x
            val downY = event.y
            val upX = event.x
            val upY = event.y
            val deltaX = kotlin.math.abs(downX - upX)
            val deltaY = kotlin.math.abs(downY - upY)
            return deltaX < 10 && deltaY < 10 // 简单的点击判断阈值
        }
        return false
    }

}
