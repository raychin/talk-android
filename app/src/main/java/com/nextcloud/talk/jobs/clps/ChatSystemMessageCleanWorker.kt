/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.jobs.clps

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import autodagger.AutoInjector
import com.nextcloud.talk.application.NextcloudTalkApplication
import com.nextcloud.talk.data.source.local.TalkDatabase

/**
 * 系统消息清除任务
    // 清理过期的系统消息
    val cleanWork = OneTimeWorkRequest.Builder(ChatSystemMessageCleanWorker::class.java)
    .addTag("chat_system_message_clean")
    .build()
    workManager.enqueueUniqueWork(
    "ChatSystemMessageCleanWork",
    ExistingWorkPolicy.KEEP,
    cleanWork
    )
 */
@AutoInjector(NextcloudTalkApplication::class)
class ChatSystemMessageCleanWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    companion object {
        private val TAG = ChatSystemMessageCleanWorker::class.java.simpleName
    }

    override fun doWork(): Result {
        Log.d(TAG, "=== ChatSystemMessageCleanWorker started ===")
        NextcloudTalkApplication.Companion.sharedApplication!!.componentApplication.inject(this)

        return try {
            val currentTimestamp = (System.currentTimeMillis() / 1000).toInt()
            val database = TalkDatabase.getInstance(applicationContext)
            val dao = database.chatMessagesDao()

            // val deleteIds = dao.getDeletableExpiredMessageIds(currentTimestamp)
            // Log.d(TAG, "Deleted deleteIds $deleteIds")
            Log.d(TAG, "currentTimestamp $currentTimestamp")

            val deletedRecallCount = dao.deleteExpiredMessages(currentTimestamp)
            Log.d(TAG, "Deleted $deletedRecallCount expired system messages")

            val deletedCount = dao.deleteExpiredSystemMessages(currentTimestamp)
            Log.d(TAG, "Deleted $deletedCount expired system messages")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean expired system messages", e)
            Result.retry()
        }
    }
}

