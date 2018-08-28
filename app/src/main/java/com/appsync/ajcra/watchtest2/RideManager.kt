package com.appsync.ajcra.watchtest2

import android.arch.persistence.room.Room
import android.content.Context
import android.util.Log
import com.amazonaws.mobileconnectors.apigateway.ApiClientFactory
import com.amazonaws.mobileconnectors.appsync.fetcher.AppSyncResponseFetchers
import com.apollographql.apollo.fetcher.ResponseFetcher
import com.appsync.ajcra.watchtest2.model.RideInfo
import com.appsync.ajcra.watchtest2.model.RideInfoInfo
import com.appsync.ajcra.watchtest2.model.RideInfoTime
import com.dis.ajcra.fastpass.fragment.DisRide
import com.dis.ajcra.fastpass.fragment.DisRideTime
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import okhttp3.Response
import java.text.SimpleDateFormat
import kotlin.coroutines.experimental.suspendCoroutine
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import java.util.*


class RideManager {
    companion object {
        fun GetInstance(ctx: Context): RideManager {
            if (rideManager == null) {
                rideManager = RideManager(ctx)
            }
            return rideManager!!
        }

        private var LIST_TIME_DIF = 30000
        var rideManager: RideManager? = null
    }

    private var apiClient: DisneyAppClient
    var crDb: RideCacheDatabase
    private var rides = ArrayList<CRInfo>()
    private var dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private var lastListTime: Date? = null
    private var reqCount: Int = 0
    private var subscribers = HashSet<ListRidesCB>()

    constructor(ctx: Context) {
        crDb = Room.databaseBuilder(ctx, RideCacheDatabase::class.java, "rides4").build()
        var cognitoManager = CognitoManager.GetInstance(ctx)
        val factory = ApiClientFactory()
                .credentialsProvider(cognitoManager.credentialsProvider)
        apiClient = factory.build(DisneyAppClient::class.java)
    }

    fun initRide(crInfo: CRInfo, rideID: String, rideInfo: RideInfoInfo, rideTime: RideInfoTime?) {
        crInfo.id = rideID
        crInfo.name = rideInfo.name
        if (rideInfo.picUrl != null) {
            //Log.d("POOP", "POOP: " + rideInfo.name + ": " + rideInfo.picUrl)
            crInfo.picURL = rideInfo.picUrl
        }
        if (rideInfo.land != null) {
            crInfo.land = rideInfo.land
        }
        if (rideInfo.height != null) {
            crInfo.height = rideInfo.height
        }
        if (rideTime != null) {
            setRideTime(crInfo, rideTime)
        }
    }

    fun setRideTime(crInfo: CRInfo, rideTime: RideInfoTime): Boolean {
        var timestamp = dateTimeFormat.parse(rideTime.dateTime).time
        var previousTimestamp = crInfo.lastChangeTime
        if (previousTimestamp == null || previousTimestamp < timestamp) {
            if (rideTime.waitRating != null) {
                crInfo.waitRating = rideTime.waitRating.toDouble()
            } else {
                crInfo.waitRating = null
            }
            if (rideTime.waitTime != null) {
                crInfo.waitTime = rideTime.waitTime
            } else {
                crInfo.waitTime = null
            }
            if (rideTime.fastPassTime != null) {
                crInfo.fpTime = rideTime.fastPassTime
            } else {
                crInfo.fpTime = null
            }
            if (rideTime.status != null) {
                crInfo.status = rideTime.status
            } else {
                crInfo.status = null
            }
            crInfo.lastChangeTime = timestamp
            return true
        }
        return false
    }

    fun handleUpdatedRideList(rideUpdates: List<RideInfo>) {
        for (rideUpdate in rideUpdates) {
            var rideI = rides.binarySearch {
                it.name.compareTo(rideUpdate.info.name)
            }
            if (rideI >= 0) {
                var ride = rides.get(rideI)
                if (setRideTime(ride, rideUpdate.time)) {
                    for (subscriber in subscribers) {
                        subscriber.onUpdate(ride)
                    }
                    cacheRideTime(ride)
                }
            } else {
                var ride = CRInfo()
                initRide(ride, rideUpdate.id, rideUpdate.info, rideUpdate.time)
                var insertIdx = -(rideI + 1)
                rides.add(insertIdx, ride)
                for (subscriber in subscribers) {
                    subscriber.onAdd(ride)
                }
                cacheRide(ride)
            }
        }
    }

    interface ListRidesCB {
        fun init(rides: ArrayList<CRInfo>)
        fun onAdd(ride: CRInfo)
        fun onUpdate(ride: CRInfo)
        fun onAllUpdated()
    }

    fun subscribe(cb: ListRidesCB) {
        subscribers.add(cb)
    }

    fun unsubscribe(cb: ListRidesCB) {
        subscribers.remove(cb)
    }

    fun listRides(cb: ListRidesCB) {
        if (!subscribers.contains(cb)) {
            subscribe(cb)
        }
        var now = Date()
        Log.d("STATE", "Gra")
        if (lastListTime == null || now.time - lastListTime!!.time > LIST_TIME_DIF) {
            reqCount = 0
            lastListTime = Date()
            _listRides()
        } else {
            cb.init(rides)
            if (reqCount >= 2) {
                cb.onAllUpdated()
            }
        }
    }

    private fun _listRides() {
        Log.d("STATE", "beep")
        var requestCompleteCount = 0
        var cachedRidesJob = async(UI) {
            for (subscriber in subscribers) {
                subscriber.init(rides)
            }
        }
        async {
            try {
                var result = apiClient.getridesGet("a", "b", "getRides")
                async(UI) {
                    cachedRidesJob.await()
                    handleUpdatedRideList(result)
                    requestCompleteCount++
                    if (requestCompleteCount > 1) {
                        for (subscriber in subscribers) {
                            subscriber.onAllUpdated()
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e("GET EX", ex.message)
            }
        }
        async {
            try {
                var result = apiClient.getridesGet("a", "b", "updateRides")
                async(UI) {
                    cachedRidesJob.await()
                    handleUpdatedRideList(result)
                    requestCompleteCount++
                    if (requestCompleteCount > 1) {
                        for (subscriber in subscribers) {
                            subscriber.onAllUpdated()
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e("GET EX", ex.message)
            }
        }
    }

    fun getMemCachedRide(rideID: String): CRInfo? {
        for (ride in rides) {
            if (ride.id == rideID) {
                return ride
            }
        }
        return null
    }

    interface GetRideCB {
        fun onUpdate(ride: CRInfo)
        fun onFinalUpdate(ride: CRInfo)
    }

    fun getRide(rideID: String, cb: GetRideCB): ListRidesCB {
        var lrcb = object: ListRidesCB {
            override fun init(rides: ArrayList<CRInfo>) {
                for (ride in rides) {
                    if (ride.id == rideID) {
                        cb.onUpdate(ride)
                    }
                }
            }

            override fun onAdd(ride: CRInfo) {
                if (ride.id == rideID) {
                    cb.onUpdate(ride)
                }
            }

            override fun onUpdate(ride: CRInfo) {
                if (ride.id == rideID) {
                    cb.onUpdate(ride)
                }
            }

            override fun onAllUpdated() {
                for (ride in rides) {
                    if (ride.id == rideID) {
                        cb.onFinalUpdate(ride)
                    }
                }
            }
        }
        listRides(lrcb)
        return lrcb
    }

    private fun initRides() = async {
        Log.d("STATE", "Zoinks")
        if (rides.isEmpty()) {
            var cachedRideList = getCachedRides().await()
            rides.addAll(cachedRideList)
            Log.d("STATE", "ADDED")
        }
    }

    fun updatePinned(rideID: String, pinned: Boolean) = async {
        async(UI) {
            for (ride in rides) {
                if (rideID == ride.id) {
                    ride.pinned = pinned
                }
            }
        }
        crDb.crInfoDao().updateRidePinned(rideID, pinned)
    }

    private fun cacheRide(ride: CRInfo) = async {
        crDb.crInfoDao().addCRInfo(ride)
    }

    private fun cacheRideTime(ride: CRInfo) = async {
        crDb.crInfoDao().updateRideTime(ride.id, ride.status, ride.waitTime, ride.fpTime, ride.waitRating, ride.lastChangeTime)
    }

    private fun getCachedRides(): Deferred<List<CRInfo>> = async {
        var res = crDb.crInfoDao().listCacheRides()
        Log.d("STATE", "HMMM")
        res
    }

    private fun getCachedRides(pinned: Boolean): Deferred<List<CRInfo>> = async {
        crDb.crInfoDao().listCacheRideOfPin(pinned)
    }
}