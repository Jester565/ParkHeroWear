package com.appsync.ajcra.watchtest2

import android.util.Log
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.Request
import java.io.File
import java.util.*

class HttpDownloadRequest {
    constructor(id: Int, listener: TransferListener) {
        this.id = id
        this.listener = listener
    }

    fun start(remoteURI: String, downloadFile: File) {
        state = TransferState.IN_PROGRESS
        //listener.onStateChanged(id, state)
        //make download request for url
        Log.d("EntitySent", "Downloading " + remoteURI)
        try {
            this.req = Fuel.download(remoteURI).destination { response, url ->
                downloadFile
            }.progress { readBytes, totalBytes ->
                //update progress of listeners
                this.bytesCurrent = readBytes
                this.bytesTotal = totalBytes
                listener.onProgressChanged(id, readBytes, totalBytes)
            }.interrupt { request ->
                //let listers know there was an error
                Log.d("EntitySent", "Interrupt")
                this.state = TransferState.FAILED
                listener.onStateChanged(id, state)
                listener.onError(id, Exception("Http Request was Interrupted"))
            }.response { request, response, result ->
                if (response.statusCode in 200..299) {
                    Log.d("EntitySent", "STATUS CODE 200")
                    state = TransferState.COMPLETED
                    listener.onStateChanged(id, state)
                } else {
                    state = TransferState.FAILED
                    listener.onStateChanged(id, state)
                    Log.d("EntitySent", "skitly bob bog ")
                    Log.d("EntitySent", "scraw " + result)
                    Log.d("EntitySent", "Bob " + request)
                    listener.onError(id, Exception("Http response gra status code " + response.statusCode))
                }
            }
        } catch(ex: Exception) {
            Log.d("STATE", "HttpEx: " + ex.message)
        }
        Log.d("STATE", "Finished creating http req")
    }

    var id: Int
    var state: TransferState = TransferState.WAITING
    var bytesCurrent: Long = 0
    var bytesTotal: Long = 0
    lateinit var req: Request
    var listener: TransferListener
}

class HttpDownloadUtility {
    constructor()
    {

    }

    fun download(remoteUrl: String, downloadFile: File, listener: TransferListener): Int {
        var id = idCounter++

        var downloadReq = HttpDownloadRequest(id, object : TransferListener {
            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                listener.onProgressChanged(id, bytesCurrent, bytesTotal)
            }

            override fun onStateChanged(id: Int, state: TransferState?) {
                listener.onStateChanged(id, state)
                if (state == TransferState.COMPLETED) {
                    requests.remove(id)
                }
            }

            override fun onError(id: Int, ex: java.lang.Exception?) {
                listener.onError(id, ex)
                requests.remove(id)
            }

        })
        requests[id] = downloadReq
        downloadReq.start(remoteUrl, downloadFile)
        return id
    }

    fun getDownload(id: Int): HttpDownloadRequest? {
        return requests[id]
    }

    fun getDownloads(): List<HttpDownloadRequest> {
        return LinkedList<HttpDownloadRequest>(requests.values)
    }

    fun cancel(id: Int) {
        var request = requests[id]
        if (request != null) {
            request.req.cancel()
        }
    }
    private var requests: HashMap<Int, HttpDownloadRequest> = HashMap<Int, HttpDownloadRequest>()
    private var idCounter: Int = 0
}