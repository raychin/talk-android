package com.nextcloud.talk.imagepicker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoEditorView

class ImageEditActivity : AppCompatActivity() {
    private lateinit var photoEditorView: PhotoEditorView
    private lateinit var photoEditor: PhotoEditor
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ip_activity_image_edit)

        photoEditorView = findViewById(R.id.photoEditorView)
        photoEditor = PhotoEditor.Builder(this, photoEditorView)
            .setPinchTextScalable(true)
            .build()
    }
}
