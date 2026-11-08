package com.tobirg.rootmate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/** Permission checks shared by the screens and the flow. */
object Perms {

    /**
     * Plain java.io.File access to shared storage and the USB drive: "All files access" on Android 11+,
     * the storage runtime permission before (Environment.isExternalStorageManager does not exist there).
     */
    fun hasStorage(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}
