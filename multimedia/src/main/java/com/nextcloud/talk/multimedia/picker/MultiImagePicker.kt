/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2025 Your Name <your@email.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// multimedia-module/src/main/java/com/nextcloud/talk/multimedia/picker/MultiImagePicker.kt
package com.nextcloud.talk.multimedia.picker

import androidx.appcompat.app.AppCompatActivity
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat

class MultiImagePicker {
    
    fun pickMultipleImages(
        activity: AppCompatActivity,
        maxSelection: Int = 10,
        onResult: (List<Uri>) -> Unit,
        onError: (Exception) -> Unit
    ): ActivityResultLauncher<Unit> {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            try {
                if (uris.isNotEmpty()) {
                    onResult(uris)
                } else {
                    onResult(emptyList())
                }
            } catch (e: Exception) {
                onError(e)
            }
        }
        
        return object : ActivityResultLauncher<Unit>() {
            override fun launch(input: Unit?) {
                try {
                    launcher.launch("image/*")
                } catch (e: Exception) {
                    onError(e)
                }
            }

            override fun launch(input: Unit?, options: ActivityOptionsCompat?) {
                TODO("Not yet implemented")
            }

            override fun unregister() {
                launcher.unregister()
            }

            override fun getContract(): ActivityResultContract<Unit?, *> {
                TODO("Not yet implemented")
            }
        }
    }
}
