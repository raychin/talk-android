/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// multimedia-module/src/main/java/com/nextcloud/talk/multimedia/cropper/ImageCropper.kt
package com.nextcloud.talk.multimedia.cropper

import android.app.Activity
import android.net.Uri
import com.yalantis.ucrop.UCrop

class ImageCropper {
    
    fun cropImage(
        activity: Activity,
        uri: Uri,
        options: CropOptions = CropOptions(),
        onResult: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            val uCrop = UCrop.of(uri, Uri.fromFile(createTempFile(activity)))
            
            val uCropOptions = com.yalantis.ucrop.UCrop.Options().apply {
                if (options.aspectRatio != null) {
                    withAspectRatio(options.aspectRatio.first.toFloat(), options.aspectRatio.second.toFloat())
                }
                
                if (options.maxWidth != null && options.maxHeight != null) {
                    withMaxResultSize(options.maxWidth, options.maxHeight)
                }
                
                setCompressionQuality(options.compressQuality)
                
                if (options.circleCrop) {
                    setCircleDimmedLayer(true)
                }
                
                setHideBottomControls(options.hideBottomControls)
            }
            
            uCrop.withOptions(uCropOptions)
            uCrop.start(activity)
            
            // 注意：UCrop的结果需要在Activity的onActivityResult中处理
            // 这里需要一个回调机制来处理结果
        } catch (e: Exception) {
            onError(e)
        }
    }
    
    private fun createTempFile(activity: Activity): java.io.File {
        return java.io.File.createTempFile("cropped_image", ".jpg", activity.cacheDir)
    }
}
