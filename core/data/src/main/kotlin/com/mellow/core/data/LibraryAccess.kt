package com.mellow.core.data

import android.Manifest
import android.os.Build

object LibraryAccess {

    /** The permission that lets Mellow read the phone's music. Android 13 renamed it. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
}
