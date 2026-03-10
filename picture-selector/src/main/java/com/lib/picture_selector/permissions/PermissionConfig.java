package com.lib.picture_selector.permissions;

import android.Manifest;
import android.os.Build;

/**
 * @author：luck
 * @date：2021/12/11 8:24 下午
 * @describe：PermissionConfig
 */
public class PermissionConfig {
    /**
     * 当前申请权限
     */
    public static String[] CURRENT_REQUEST_PERMISSION = null;


    // 根据 Android 版本动态选择权限
    public static String[] getReadExternalStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用新的媒体权限
            return new String[]{
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        } else {
            // Android 9 及以下
            return new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }
    }
    /**
     * 读写权限
     */
//    public final static String[] READ_WRITE_EXTERNAL_STORAGE =
//            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
//                    Manifest.permission.WRITE_EXTERNAL_STORAGE};
    public static final String[] READ_WRITE_EXTERNAL_STORAGE = getReadExternalStoragePermissions();

    /**
     * 写入权限
     */
    public final static String[] WRITE_EXTERNAL_STORAGE = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE};

    /**
     * 相机权限
     */
    public final static String[] CAMERA = new String[]{Manifest.permission.CAMERA};

}
