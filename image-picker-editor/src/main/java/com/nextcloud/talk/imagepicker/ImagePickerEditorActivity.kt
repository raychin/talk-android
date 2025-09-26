package com.nextcloud.talk.imagepicker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.PictureMimeType
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnMediaEditInterceptListener
import com.luck.picture.lib.interfaces.OnResultCallbackListener
import com.luck.picture.lib.manager.SelectedManager
import com.luck.picture.lib.utils.ActivityCompatHelper.assertValidRequest
import com.luck.picture.lib.utils.DateUtils
import com.luck.picture.lib.widget.BottomNavBar
import com.luck.picture.lib.widget.CompleteSelectView
import com.luck.picture.lib.widget.MediumBoldTextView
import com.luck.picture.lib.widget.PreviewBottomNavBar
import com.luck.picture.lib.widget.PreviewTitleBar
import com.luck.picture.lib.widget.TitleBar
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropImageEngine
import java.io.File

class ImagePickerEditorActivity : AppCompatActivity() {

    // private lateinit var binding: ActivityImagePickerEditorBinding
    private lateinit var adapter: ImagePreviewAdapter
    private var imageList: MutableList<LocalMedia> = mutableListOf()
    private var selectList: MutableList<LocalMedia> = mutableListOf()
    private var currentPosition = 0
    private lateinit var viewPager: ViewPager2
    private lateinit var tvIndicator: TextView
    private lateinit var btnClose: ImageButton
    private fun setupViewPager() {
        adapter = ImagePreviewAdapter(imageList) { _, _, _ ->
            // 单击图片时隐藏/显示UI
            toggleUIVisibility()
        }

        viewPager.adapter = adapter
        viewPager.setCurrentItem(currentPosition, false)

        // 页面切换监听
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentPosition = position
                updateIndicator()
            }
        })
    }

    private fun updateIndicator() {
        titleBar.setTitle("${currentPosition + 1}/${imageList.size}")
        tvIndicator.text = "${currentPosition + 1}/${imageList.size}"

        var currentMedia = imageList.get(currentPosition)
        // 判断selectList是否包含currentMedia数据体
        var selectResultCode = selectList.contains(currentMedia)

        if (selectResultCode) {
            tvSelected.isSelected = false
        } else  {
            tvSelected.isSelected = true
        }
    }

    private fun toggleUIVisibility() {
        if (btnClose.visibility == View.VISIBLE) {
            hideUI()
        } else {
            showUI()
        }
    }

    private fun hideUI() {
        btnClose.visibility = View.GONE
        tvIndicator.visibility = View.GONE
    }

    private fun showUI() {
        btnClose.visibility = View.VISIBLE
        tvIndicator.visibility = View.VISIBLE
    }

    protected lateinit var titleBar: PreviewTitleBar
    protected lateinit var selectClickArea: View
    protected lateinit var tvSelected: MediumBoldTextView

    private fun initTitleBar() {
        titleBar.setTitleBarStyle()
        // titleBar.setBackgroundColor(resources.getColor(R.color.ip_color_black))
        titleBar.setOnTitleBarListener(object : TitleBar.OnTitleBarListener() {
            override fun onBackPressed() {
                finish()
            }
        })
        titleBar.setTitle("${currentPosition + 1}/${imageList.size}")
        titleBar.imageDelete.setOnClickListener {
            // deletePreview()
        }
        selectClickArea.setOnClickListener {
            var currentMedia = imageList.get(currentPosition)
            // 判断selectList是否包含currentMedia数据体
            var selectResultCode = selectList.contains(currentMedia)

            if (selectResultCode) {
                selectList.remove(currentMedia)
                tvSelected.isSelected = false
            } else  {
                selectList.add(currentMedia)
                tvSelected.isSelected = true
            }
        }
        // tvSelected.setOnClickListener(object : View.OnClickListener {
        //     override fun onClick(view: View?) {
        //         selectClickArea.performClick()
        //     }
        // })
    }

    protected lateinit var bottomNarBar: PreviewBottomNavBar
    protected lateinit var completeSelectView: CompleteSelectView
    private fun initBottomNavBar() {
        bottomNarBar.setBottomNavBarStyle();
        bottomNarBar.setSelectedChange();
        bottomNarBar.isDisplayEditor(true)
        bottomNarBar.setOnBottomNavBarListener(object : BottomNavBar.OnBottomNavBarListener() {
            override fun onEditImage() {
                super.onEditImage()
                val path = imageList[currentPosition].path
                // Implement preview for each image
                val intent = Intent(this@ImagePickerEditorActivity, ImageEditActivity::class.java).apply {
                    putExtra("image_path", path)
                }
                startActivity(intent)
            }

            override fun onCheckOriginalChange() {
                super.onCheckOriginalChange()
            }

            override fun onFirstCheckOriginalSelectedChange() {
                super.onFirstCheckOriginalSelectedChange()
            }
        })
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ip_activity_image_picker_editor)
        setImmersive()

        viewPager = findViewById(R.id.viewPager)
        tvIndicator = findViewById(R.id.tvIndicator)
        btnClose = findViewById(R.id.btnClose)

        titleBar = findViewById<PreviewTitleBar>(R.id.title_bar)
        selectClickArea = findViewById<View>(R.id.select_click_area)
        tvSelected = findViewById<MediumBoldTextView>(R.id.ps_tv_selected)
        initTitleBar()

        bottomNarBar = findViewById<PreviewBottomNavBar>(R.id.bottom_nar_bar)
        initBottomNavBar()

        completeSelectView = findViewById(R.id.ps_complete_select);

        openGallery()
    }

    private fun setImmersive () {
        // 设置透明状态栏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        } else {
            // 使用旧版方法
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    private fun openGallery() {
        PictureSelector.create(this)
            .openGallery(SelectMimeType.ofImage())
            .setImageEngine(GlideEngine.createGlideEngine())
            // 设置该属性则进入 编辑
            // .setCropEngine(ImageFileCropEngine())
            .setEditMediaInterceptListener(MeOnMediaEditInterceptListener(getSandboxPath(), buildOptions()))
            // .maxSelectNum(9)
            .forResult(object : OnResultCallbackListener<LocalMedia> {
                // override fun onResult(result: List<LocalMedia>) {
                override fun onResult(result: ArrayList<LocalMedia>) {
                    if (result.isNotEmpty()) {
                        // Handle multiple images
                        imageList = mutableListOf()
                        selectList = mutableListOf()
                        result.forEach { media ->
                            // val path = if (media.isCut) media.cutPath else media.path
                            // // Implement preview for each image
                            // val intent = Intent(this@ImagePickerEditorActivity, ImagePreviewActivity::class.java).apply {
                            //     putExtra("image_path", path)
                            // }
                            // startActivity(intent)
                            imageList.add(media)

                        }
                        setupViewPager()

                        // For now, handle the first image as before
                        // val media = result[0]
                        // val path = if (media.isCut) media.cutPath else media.path
                        // photoEditorView.source.setImageURI(Uri.parse(path))
                    }
                }

                override fun onCancel() {
                    finish()
                }
            })
    }

    private fun buildOptions(): UCrop.Options {
        val options = UCrop.Options()
        options.setCompressionQuality(80) // 压缩质量
        options.setHideBottomControls(false) // 是否隐藏底部控制栏
        options.setFreeStyleCropEnabled(true) // 是否可以自由裁剪
        options.isForbidSkipMultipleCrop(true);
        return options
    }

    /**
     * 创建自定义输出目录
     *
     * @return
     */
    private fun getSandboxPath(): String {
        val externalFilesDir: File? = applicationContext.getExternalFilesDir("")
        val customFile = File(externalFilesDir?.absolutePath, "Sandbox")
        if (!customFile.exists()) {
            customFile.mkdirs()
        }
        return customFile.absolutePath + File.separator
    }

    /**
     * 自定义编辑
     */
    private class MeOnMediaEditInterceptListener(private val outputCropPath: String?, private val options:
    UCrop.Options) :
        OnMediaEditInterceptListener {
        @SuppressLint("UseKtx")
        override fun onStartMediaEdit(fragment: Fragment, currentLocalMedia: LocalMedia, requestCode: Int) {
            val currentEditPath = currentLocalMedia.getAvailablePath()
            val inputUri = if (PictureMimeType.isContent(currentEditPath))
                currentEditPath.toUri()
            else
                Uri.fromFile(File(currentEditPath))
            val destinationUri = Uri.fromFile(
                File(outputCropPath, DateUtils.getCreateFileName("CROP_") + ".jpeg")
            )
            val uCrop = UCrop.of<Any?>(inputUri, destinationUri)
            options.setHideBottomControls(false)
            uCrop.withOptions(options)
            uCrop.setImageEngine(object : UCropImageEngine {
                override fun loadImage(context: Context, url: String?, imageView: ImageView) {
                    if (!assertValidRequest(context)) {
                        return
                    }
                    Glide.with(context).load(url).override(180, 180).into(imageView)
                }

                override fun loadImage(
                    context: Context,
                    url: Uri?,
                    maxWidth: Int,
                    maxHeight: Int,
                    call: UCropImageEngine.OnCallbackListener<Bitmap?>?
                ) {
                    Glide.with(context).asBitmap().load(url).override(maxWidth, maxHeight)
                        .into(object : CustomTarget<Bitmap?>() {

                            override fun onResourceReady(
                                resource: Bitmap,
                                transition: com.bumptech.glide.request.transition.Transition<in Bitmap?>?
                            ) {
                                call?.onCall(resource)
                            }

                            override fun onLoadCleared(placeholder: Drawable?) {
                                call?.onCall(null)
                            }
                        })
                }
            })
            uCrop.startEdit(fragment.requireActivity(), fragment, requestCode)
        }
    }

    companion object {
        const val EXTRA_SELECTED_IMAGES = "selected_images"
        const val REQUEST_CODE_PICK_IMAGES = 1001
        fun start(activity: Activity) {
            val intent = Intent(activity, ImagePickerEditorActivity::class.java)
            activity.startActivity(intent)
        }
    }

    // TODO 处理数据回调
    // private fun handleSelectedImages(images: ArrayList<LocalMedia>) {
    //     if (images.isNotEmpty()) {
    //         val imagePaths = images.map { it.availablePath }.toTypedArray()
    //         val intent = Intent().apply {
    //             putExtra(EXTRA_SELECTED_IMAGES, imagePaths)
    //         }
    //         setResult(Activity.RESULT_OK, intent)
    //         finish()
    //     } else {
    //         setResult(Activity.RESULT_CANCELED)
    //         finish()
    //     }
    // }
}
