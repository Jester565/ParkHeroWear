package com.appsync.ajcra.watchtest2

import android.arch.persistence.room.Room
import android.content.Context
import android.util.Log
import com.amazonaws.HttpMethod
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferType
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest
import com.amazonaws.services.s3.model.GetObjectMetadataRequest
import com.amazonaws.services.s3.model.S3ObjectSummary
import com.appsync.ajcra.watchtest2.CloudFileManager.Companion.BUCKET_NAME
import kotlinx.coroutines.experimental.async
import java.io.File
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

abstract class CloudFileObserver: TransferListener {
    var utilID: AtomicInteger = AtomicInteger(-1)
    var type: TransferType
    var file: File
    private var cancelOnNoListeners: Boolean
    protected var active: Boolean = true
    private var listeners: MutableSet<CloudFileListener> = Collections.newSetFromMap(ConcurrentHashMap<CloudFileListener, Boolean>())

    constructor(type: TransferType, file: File, cancelOnNoListeners: Boolean = false) {
        this.type = type
        this.file = file
        this.cancelOnNoListeners = cancelOnNoListeners
    }

    fun checkRunning(id: Int) {
        this.utilID.set(id)
        synchronized(active) {
            if (!active) {
                cancel()
            }
        }
    }

    override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
        checkRunning(id)
        for (listener in listeners) {
            listener.onProgressChanged(id, bytesCurrent, bytesTotal)
        }
    }

    override fun onStateChanged(id: Int, state: TransferState?) {
        Log.d("STATE", "SUPER ON STATE CHANGE CALLED")
        checkRunning(id)
        for (listener in listeners) {
            listener.onStateChanged(id, state)
            if (state == TransferState.COMPLETED) {
                listener.onComplete(id, file)
            }
        }
    }

    override fun onError(id: Int, ex: java.lang.Exception?) {
        this.utilID.set(id)
        cancel()
        for (listener in listeners) {
            listener.onError(id, ex)
        }
    }

    @Synchronized open fun addListener(listener: CloudFileListener): Boolean {
        synchronized(active) {
            if (active) {
                listeners.add(listener)
                return true
            }
            return false
        }
    }

    @Synchronized open fun removeListener(listener: CloudFileListener) {
        listeners.remove(listener)
        if (cancelOnNoListeners && listeners.isEmpty()) {
            cancel()
        }
    }

    abstract fun cancel()
}

class HttpCloudFileObserver: CloudFileObserver {
    private var cfm: CloudFileManager
    private var cfi: CloudFileInfo

    constructor(cfm: CloudFileManager, cfi: CloudFileInfo, type: TransferType, file: File, cancelOnNoListeners: Boolean = false)
            :super(type, file, cancelOnNoListeners)
    {
        this.cfm = cfm
        this.cfi = cfi
        cfm.observers.put(cfi.objKey, this)
    }

    override fun onStateChanged(id: Int, state: TransferState?) {
        async {
            Log.d("STATE", "HTTP ON STATE CHANGE CALLED")
            if (state == TransferState.COMPLETED) {
                cfi.fileURI = file.toURI().toString()
                cfi.lastAccessed = Date().time
                try {
                    //Log.d("STATE", "HERE1")
                    //cfi.lastUpdated = cfm.getLastChangedTime(cfi.objKey)
                    Log.d("STATE", "HERE2")
                    cfm.cfiDb.cloudFileInfoDao().addCloudFileInfo(cfi)
                    Log.d("STATE", "HERE3")
                } catch (ex: Exception) {
                    Log.d("STATE", "EX: " + ex.message)
                }
                cancel()
            }
            super.onStateChanged(id, state)
        }
    }

    override fun cancel() {
        synchronized(active) {
            if (active) {
                active = false
                cfm.observers.remove(cfi.objKey)
            }
        }
        if (utilID.get() >= 0) {
            cfm.httpUtility.cancel(utilID.get())
        }
    }

    @Synchronized override fun addListener(listener: CloudFileListener): Boolean {
        if (super.addListener(listener)) {
            if (utilID.get() >= 0) {
                var transfer = cfm.httpUtility.getDownload(utilID.get())
                if (transfer != null) {
                    listener.onStateChanged(utilID.get(), transfer.state)
                    listener.onProgressChanged(utilID.get(), transfer.bytesCurrent, transfer.bytesTotal)
                }
            }
            return true
        }
        return false
    }
}

class CloudFileManager {
    companion object {
        var BUCKET_NAME: String = "disneyapp3"
        private var Instance: CloudFileManager? = null
        fun GetInstance(cognitoManager: CognitoManager, appContext: Context): CloudFileManager {
            if (Instance == null) {
                Instance = CloudFileManager(cognitoManager, appContext)
            }
            return Instance as CloudFileManager
        }
    }
    var observers: ConcurrentHashMap<String, CloudFileObserver> = ConcurrentHashMap<String, CloudFileObserver>()
    var httpUtility: HttpDownloadUtility
    private var s3Client: AmazonS3
    private var appContext: Context
    private var cognitoManager: CognitoManager
    var cfiDb: CloudFileDatabase

    constructor(cognitoManager: CognitoManager, appContext: Context) {
        this.cognitoManager = cognitoManager
        this.appContext = appContext
        s3Client = AmazonS3Client(cognitoManager.credentialsProvider)
        httpUtility = HttpDownloadUtility()
        cfiDb = Room.databaseBuilder(appContext, CloudFileDatabase::class.java, "cfi").build()
    }

    suspend fun listObjects(prefix: String, orderByDate: Boolean = false): List<S3ObjectSummary>? {
        try {
            var resp = s3Client.listObjectsV2(BUCKET_NAME, prefix)
            if (orderByDate) {
                resp.objectSummaries.sortByDescending { it ->
                    it.lastModified
                }
            }
            return resp.objectSummaries
        } catch (ex: Exception) {
            Log.d("CFM", "List object exception " + ex.message)
        }
        return null
    }

    private fun getCFI(key: String): CloudFileInfo {
        try {
            var cfi = cfiDb.cloudFileInfoDao().getCloudFileInfo(key)
            if (cfi != null) {
                Log.d("CFM", "GetCFI: " + key)
                return cfi
            }
        } catch(ex: Exception) {
            Log.d("CFM", "EX: " + ex.message)
        }
        var cfi = CloudFileInfo()
        cfi.objKey = key
        return cfi
    }

    suspend fun genPresignedURI(key: String, expireMins: Int): URI {
        var cal = GregorianCalendar.getInstance(TimeZone.getTimeZone("GMT"))
        cal.add(Calendar.HOUR, 20)

        var presignUrlReq = GeneratePresignedUrlRequest(BUCKET_NAME, key)
        presignUrlReq.method = HttpMethod.GET
        presignUrlReq.expiration = cal.time

        var presignedURL = s3Client.generatePresignedUrl(presignUrlReq)
        return presignedURL.toURI()
    }

    fun getObservers(type: TransferType): Map<String, CloudFileObserver> {
        var results = HashMap<String, CloudFileObserver>()
        for (entry in observers) {
            var observer = entry.value
            if (observer.type == type || type == TransferType.ANY) {
                results.put(entry.key, observer)
            }
        }
        return results
    }

    suspend fun getCachedFile(cfi: CloudFileInfo, checkUpdate: Boolean = false): File? {
        Log.d("CFM", "Attempting to get cached file")
        if (cfi.fileURI != null) {
            Log.d("CFM", "Cache hit for " + cfi.objKey)
            var file = File(URI(cfi.fileURI))
            if (file.exists()) {
                if (!checkUpdate || getLastChangedTime(cfi.objKey) <= cfi.lastUpdated) {
                    return file
                }
            }
        }
        return null
    }

    suspend fun getLastChangedTime(key: String): Long {
        var metaReq = GetObjectMetadataRequest(BUCKET_NAME, key)
        var metadata = s3Client.getObjectMetadata(metaReq)
        return metadata.lastModified.time
    }

    @Synchronized suspend fun download(key: String, listener: CloudFileListener, givenPresignedURI: String? = null, checkUpdate: Boolean = false) {
        //Check if we are already downloading
        run {
            var cloudFileObserver = observers[key]
            if (cloudFileObserver != null) {
                //If we listened successfully
                if (cloudFileObserver.addListener(listener)) {
                    Log.d("CFM", "Added listener to existing download")
                    return
                }
            }
        }

        //Check if the file is cached
        var cfi = getCFI(key)
        var file = getCachedFile(cfi, checkUpdate)
        if (file != null) {
            //update the last read date
            cfi.lastAccessed = Date().time
            cfiDb.cloudFileInfoDao().addCloudFileInfo(cfi)
            //call the listener with the file
            Log.d("CFM", "Got file from cache")
            listener.onComplete(0, file)
            return
        }
        Log.d("CFM", "Downloading file: " + key)
        var downloadFile = File(appContext.cacheDir, UUID.randomUUID().toString())
        downloadFile.createNewFile()
        var presignedURI: URI
        if (givenPresignedURI == null) {
            presignedURI = genPresignedURI(cfi.objKey, 60)
        } else {
            presignedURI = URI(givenPresignedURI)
        }
        var cloudFileObserver = HttpCloudFileObserver(this, cfi, TransferType.DOWNLOAD, downloadFile, true)
        cloudFileObserver.addListener(listener)
        Log.d("CFM", "HttpDownload")
        httpUtility.download(presignedURI.toString(), downloadFile, cloudFileObserver)
    }

    suspend fun clearCache(maxMB: Float = 0f) {
        //TODO: Check if there are active observers before deleting
        var megs = 0f
        var cfis = cfiDb.cloudFileInfoDao().getCloudFileInfosNewestToOldest()
        for (cfi in cfis) {
            var file = File(URI(cfi.fileURI))
            if (file.exists()) {
                var fmb = (file.length() / 1024.0f) / 1024.0f
                if (fmb + megs > maxMB) {
                    cfiDb.cloudFileInfoDao().delete(cfi)
                    file.delete()
                } else {
                    megs += fmb
                }
            } else {
                cfiDb.cloudFileInfoDao().delete(cfi)
            }
        }
    }

    @Synchronized suspend fun delete(key: String) {
        var observer = observers[key]
        observer?.cancel()
        var cfi = getCFI(key)
        cfiDb.cloudFileInfoDao().delete(cfi)
        var file = File(URI(cfi.fileURI))
        if (file.exists()) {
            file.delete()
        }
        s3Client.deleteObject(BUCKET_NAME, cfi.objKey)
    }

    fun displayFileInfo() {
        Log.d("CFM", "Database...")
        var cfis = cfiDb.cloudFileInfoDao().getCloudFileInfosOldestToNewest()
        for (cfi in cfis) {
            Log.d("CFM", "CFI: " + cfi)
            if (cfi.fileURI != null) {
                if (!File(URI(cfi.fileURI)).exists()) {
                    Log.d("CFM", "WARNING file does not exist")
                }
            }
            Log.d("CFM", "\n")
        }
        Log.d("CFM", "Http Transfers...")
        var httpDownloads = httpUtility.getDownloads()
        for (httpDownload in httpDownloads) {
            Log.d("CFM", "HttpDownload: " + httpDownload.state + " : " + httpDownload.req.url + "\n")
        }
        Log.d("CFM", "Observers")
        for (observer in observers) {
            Log.d("CFM", "Observer: " + observer.key)
        }
    }
}
