/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.imagepicker

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * @Description: 类说明
 * @Author: ray
 * @Date: 21/9/25
 */
class ImageLoaderUtils {
    fun assertValidRequest(context: Context?): Boolean {
        if (context is Activity) {
            val activity = context
            return !isDestroy(activity)
        } else if (context is ContextWrapper) {
            val contextWrapper = context
            if (contextWrapper.getBaseContext() is Activity) {
                val activity = contextWrapper.getBaseContext() as Activity?
                return !isDestroy(activity)
            }
        }
        return true
    }

    private fun isDestroy(activity: Activity?): Boolean {
        if (activity == null) {
            return true
        }
        return activity.isFinishing() || activity.isDestroyed()
    }
}
