package com.nextcloud.talk.imagepicker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.isVisible
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
import com.luck.picture.lib.utils.ActivityCompatHelper.assertValidRequest
import com.luck.picture.lib.utils.DateUtils
import com.luck.picture.lib.widget.BottomNavBar
import com.luck.picture.lib.widget.CompleteSelectView
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
    private lateinit var tvEdit: TextView
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
            ivSelected.isSelected = false
        } else  {
            ivSelected.isSelected = true
        }
    }

    private fun toggleUIVisibility() {
        if (btnClose.isVisible) {
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
    protected lateinit var selectLayout: LinearLayout
    protected lateinit var ivSelected: ImageView

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
        selectLayout.setOnClickListener {
            var currentMedia = imageList.get(currentPosition)
            // 判断selectList是否包含currentMedia数据体
            var selectResultCode = selectList.contains(currentMedia)

            if (selectResultCode) {
                selectList.remove(currentMedia)
                ivSelected.isSelected = false
            } else  {
                selectList.add(currentMedia)
                ivSelected.isSelected = true
            }
        }
        // ivSelected.setOnClickListener(object : View.OnClickListener {
        //     override fun onClick(view: View?) {
        //         selectClickArea.performClick()
        //     }
        // })
    }

    private lateinit var imageEditLauncher: ActivityResultLauncher<Intent>

    fun onChooseImages(uri: Uri, saveToPath: String) {
        val intent = Intent(this, ImageEditActivity::class.java)
        intent.putExtra("image_path", uri)
        intent.putExtra("image_path_save", saveToPath)
        imageEditLauncher.launch(intent)
        // startActivity(intent)
        // startActivityForResult(intent, REQ_IMAGE_EDIT)
    }

    // override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    //     super.onActivityResult(requestCode, resultCode, data)
    //     when (requestCode) {
    //         REQ_IMAGE_EDIT -> {
    //             if (resultCode == Activity.RESULT_OK) {
    //                 onImageEditDone()
    //             }
    //         }
    //     }
    // }

    fun onImageEditDone() {
        // TODO do some thins
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

                onChooseImages(Uri.fromFile(File(path)), imageList[currentPosition].realPath)
                // // Implement preview for each image
                // val intent = Intent(this@ImagePickerEditorActivity, ImageEditActivity::class.java).apply {
                //     putExtra("image_path", path)
                // }
                // startActivity(intent)
            }

            override fun onCheckOriginalChange() {
                super.onCheckOriginalChange()
            }

            override fun onFirstCheckOriginalSelectedChange() {
                super.onFirstCheckOriginalSelectedChange()
            }
        })

        tvEdit.setOnClickListener {
            val path = imageList[currentPosition].path
            // // Implement preview for each image
            // val intent = Intent(this@ImagePickerEditorActivity, ImageEditActivity::class.java).apply {
            //     putExtra("image_path", path)
            // }
            // startActivity(intent)
            onChooseImages(Uri.fromFile(File(path)), imageList[currentPosition].realPath)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ip_activity_image_picker_editor)

        viewPager = findViewById(R.id.viewPager)
        tvIndicator = findViewById(R.id.tvIndicator)
        tvEdit = findViewById(R.id.ps_tv_edit)
        btnClose = findViewById(R.id.btnClose)

        titleBar = findViewById<PreviewTitleBar>(R.id.title_bar)
        selectLayout = findViewById<LinearLayout>(R.id.ps_ll_selected)
        ivSelected = findViewById<ImageView>(R.id.ps_iv_selected)
        initTitleBar()

        bottomNarBar = findViewById<PreviewBottomNavBar>(R.id.bottom_nar_bar)
        initBottomNavBar()

        completeSelectView = findViewById(R.id.ps_complete_select);

        imageEditLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK) {
                onImageEditDone()
            }
            // when (result.requestCode) {
            //     REQ_IMAGE_EDIT -> {
            //
            //         if (result.resultCode == Activity.RESULT_OK) {
            //             onImageEditDone()
            //         }
            //     }
            // }
        }


        openGallery()
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

                        // TODO RAY 选取图片完成逻辑处理
                        // setupViewPager()
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val files: ArrayList<Uri> = withContext(Dispatchers.IO) {
                                    // 使用 ArrayList 作为可变集合来收集结果
                                    val tempList = ArrayList<Uri>()
                                    result.forEach { media ->
                                        try {
                                            if (media.isCut && !media.cutPathContent.isNullOrEmpty()) {
                                                tempList.add(media.cutPathContent.toUri())
                                            } else if (media.isCut && !media.cutPath.isNullOrEmpty()) {
                                                val cutFile = File(media.cutPath)
                                                if (cutFile.exists()) {
                                                    // 使用正确的 authority，与 AndroidManifest.xml 中的配置保持一致
                                                    val uri = FileProvider.getUriForFile(
                                                        this@ImagePickerEditorActivity,
                                                        applicationContext.packageName,
                                                        cutFile
                                                    )
                                                    tempList.add(uri)
                                                } else {
                                                    Log.w("Ray", "Cut file does not exist: ${media.cutPath}")
                                                }
                                            } else if (!media.path.isNullOrEmpty()) {
                                                @Suppress("DEPRECATION")
                                                tempList.add(Uri.parse(media.path))
                                            } else {
                                                Log.w("Ray", "Media path is null or empty")
                                            }
                                        } catch (e: Exception) {
                                            Log.e("Ray", "Error processing media: ${e.message}", e)
                                        }
                                    }
                                    tempList
                                }


                                withContext(Dispatchers.Main) {
                                    Log.w("Ray", "files: ${files.toString()}")
                                    // 发送文件
                                    val intent = Intent()
                                    intent.putStringArrayListExtra(EXTRA_SELECTED_IMAGES, files)
                                    setResult(RESULT_OK, intent)
                                    finish()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Log.e("Ray", "Error processing files: ${e.message}", e)
                                    // 显示错误提示
                                }
                            }
                        }

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
        const val REQ_IMAGE_EDIT = 1002
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
