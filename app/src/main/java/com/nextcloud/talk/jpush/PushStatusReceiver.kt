/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.jpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.jpush.android.api.JPushInterface
import com.nextcloud.talk.services.KeepAliveManager

/**
 * 利用系统广播（覆盖被杀后重启场景）
 * 创建一个开机广播接收器，在设备重启后主动恢复推送：
 */
class PushStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                JPushInterface.getPushStatus(context)
                JPushInterface.resumePush(context)
                // 重启保活定时器
                if (KeepAliveManager.isEnabled(context)) {
                    KeepAliveManager.start(context)
                }
            }
        }
    }
}
