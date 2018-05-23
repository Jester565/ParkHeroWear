package com.appsync.ajcra.watchtest2

import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import java.io.File

/**
 * Created by ajcra on 1/3/2018.
 */

abstract class CloudFileListener {
    private var priority: Int
    constructor(priority: Int = 0) {
        this.priority = priority
    }
    fun getPriority(): Int {
        return priority
    }
    abstract fun onError(id: Int, ex: Exception?)
    abstract fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long)
    abstract fun onStateChanged(id: Int, state: TransferState?)
    abstract fun onComplete(id: Int, file: File)
}