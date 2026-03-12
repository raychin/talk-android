package com.nextcloud.talk.imagepicker

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoEditorView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ImageEditActivity : AppCompatActivity() {
    private lateinit var photoEditorView: PhotoEditorView
    private lateinit var photoEditor: PhotoEditor
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ip_activity_image_edit)

        val imagePath = intent.getStringExtra("image_path")

        photoEditorView = findViewById(R.id.photoEditorView)
        photoEditor = PhotoEditor.Builder(this, photoEditorView)
            .setPinchTextScalable(true)
            .build()

        loadImageToEditor(imagePath)

    }

    private fun loadImageToEditor(imagePath: String?) {
        if (imagePath.isNullOrBlank()) {
            finish()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            Glide.with(this@ImageEditActivity)
                .asBitmap()
                .load(imagePath)
                .into(object : SimpleTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap?>?) {
                        resource.let { bitmap ->
                            photoEditorView.source.setImageBitmap(bitmap)

                            photoEditor.setBrushDrawingMode(true)
                            photoEditor.brushSize = 20f
                            photoEditor.brushColor = Color.RED
                        }
                    }
                })
        }
    }
}
