package com.animo.ar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Tiny bridge between the recording service and the Compose overlay. */
object ShareCenter {
    /** True while the 6-second AR clip is being captured; the Play UI hides itself. */
    var recording by mutableStateOf(false)

    /** Output path handed to the activity so it can share the finished clip. */
    var pendingVideo: File? = null

    /** Set by the service once the MP4 is finalised and safe to share. */
    var lastVideo by mutableStateOf<File?>(null)
}
