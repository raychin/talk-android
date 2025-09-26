// multimedia-module/src/main/java/com/nextcloud/talk/multimedia/MultimediaModule.kt
package com.nextcloud.talk.multimedia

import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.nextcloud.talk.multimedia.cropper.CropOptions
import com.nextcloud.talk.multimedia.picker.MultiImagePicker
import com.nextcloud.talk.multimedia.editor.ImageEditor
import com.nextcloud.talk.multimedia.cropper.ImageCropper
import com.nextcloud.talk.multimedia.editor.EditOptions

class MultimediaModule(private val context: Context) {

    private val imagePicker = MultiImagePicker()
    private val imageEditor = ImageEditor()
    private val imageCropper = ImageCropper()

    fun pickMultipleImages(
        activity: AppCompatActivity,
        maxSelection: Int = 10,
        onResult: (List<Uri>) -> Unit,
        onError: (Exception) -> Unit
    ): ActivityResultLauncher<Unit> {
        return imagePicker.pickMultipleImages(activity, maxSelection, onResult, onError)
    }

    fun cropImage(
        activity: AppCompatActivity,
        uri: Uri,
        options: CropOptions = CropOptions(),
        onResult: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        imageCropper.cropImage(activity, uri, options, onResult, onError)
    }

    fun editImage(
        activity: AppCompatActivity,
        uri: Uri,
        options: EditOptions = EditOptions(),
        onResult: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        imageEditor.editImage(activity, uri, options, onResult, onError)
    }

    fun editMultipleImages(
        activity: AppCompatActivity,
        uris: List<Uri>,
        options: EditOptions = EditOptions(),
        onResult: (List<Uri>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        imageEditor.editMultipleImages(activity, uris, options, onResult, onError)
    }
}
