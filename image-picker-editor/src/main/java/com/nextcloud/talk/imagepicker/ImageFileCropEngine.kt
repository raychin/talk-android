/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// ImageFileCropEngine.kt
package com.nextcloud.talk.imagepicker

import android.content.Context
import android.net.Uri
import androidx.fragment.app.Fragment
import com.luck.picture.lib.engine.CropFileEngine
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.PictureConfig
import com.luck.picture.lib.utils.DateUtils
import com.luck.picture.lib.utils.PictureFileUtils
import com.yalantis.ucrop.UCrop
import java.io.File
import java.util.ArrayList

class ImageFileCropEngine : CropFileEngine {
    // override fun onStartCrop(
    //     fragment: Fragment,
    //     context: Context,
    //     srcUri: Uri,
    //     fileName: String,
    //     mineType: String
    // ) {
    //     // 创建裁剪配置
    //     val destinationUri = Uri.fromFile(
    //         File(
    //             File(context.cacheDir, "crop"), // 使用正确方法获取路径
    //             "CROP_" + System.currentTimeMillis() + ".jpeg"
    //         )
    //     )
    //
    //     val options = UCrop.Options()
    //     options.setCompressionQuality(80) // 压缩质量
    //     options.setHideBottomControls(false) // 是否隐藏底部控制栏
    //     options.setFreeStyleCropEnabled(true) // 是否可以自由裁剪
    //
    //     // 启动裁剪
    //     UCrop.of(srcUri, destinationUri)
    //         .withOptions(options)
    //         .start(fragment.requireActivity(), fragment)
    // }

    override fun onStartCrop(
        fragment: Fragment?,
        srcUri: Uri?,
        destinationUri: Uri?,
        dataSource: ArrayList<String?>?,
        requestCode: Int
    ) {
        // 确保 fragment、srcUri 和 destinationUri 不为空
        fragment ?: return
        srcUri ?: return
        destinationUri ?: return
        // 创建裁剪配置
        // val destinationUri = Uri.fromFile(
        //     File(
        //         File(context.cacheDir, "crop"), // 使用正确方法获取路径
        //         "CROP_" + System.currentTimeMillis() + ".jpeg"
        //     )
        // )

        val options = UCrop.Options()
        options.setCompressionQuality(80) // 压缩质量
        options.setHideBottomControls(false) // 是否隐藏底部控制栏
        options.setFreeStyleCropEnabled(true) // 是否可以自由裁剪
        options.isForbidSkipMultipleCrop(true);

        // 启动裁剪
        UCrop.of<UCrop>(srcUri, destinationUri)
            .withOptions(options)
            .start(fragment.requireActivity(), fragment)
    }
}
