package com.appsync.ajcra.watchtest2

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.fetcher.AppSyncResponseFetchers
import com.apollographql.apollo.fetcher.ResponseFetcher
import com.dis.ajcra.fastpass.fragment.DisRide
import okhttp3.Response
import kotlin.coroutines.experimental.suspendCoroutine

class RideManager {
    var appSync: AppSyncTest

    constructor(ctx: Context) {
        appSync = AppSyncTest.getInstance(ctx)
    }

    fun getRides(cb: AppSyncTest.GetRidesCallback) {
        appSync.getRides(cb)
    }

    fun getRideUpdates(cb: AppSyncTest.UpdateRidesCallback) {
        appSync.updateRides(cb)
    }

    suspend fun getRidesSuspend(requestMode: ResponseFetcher = AppSyncResponseFetchers.NETWORK_ONLY): List<DisRide> = suspendCoroutine{ cont ->
        var numUpdates = 0
        appSync.getRides(object: AppSyncTest.GetRidesCallback {
            override fun onResponse(response: List<DisRide>) {
                if (numUpdates == 0) {
                    cont.resume(response)
                }
                numUpdates++
            }

            override fun onError(ec: Int?, msg: String?) {

            }
        }, requestMode)
    }

    suspend fun getRideUpdatesSuspend(): List<DisRide>? = suspendCoroutine{ cont ->
        var numUpdates = 0
        appSync.updateRides(object: AppSyncTest.UpdateRidesCallback {
            override fun onResponse(response: List<DisRide>?) {
                if (numUpdates == 0) {
                    cont.resume(response)
                }
                numUpdates++
            }

            override fun onError(ec: Int?, msg: String?) {

            }
        })
    }

    fun subscribeToRideUpdates(cb: AppSyncTest.RideUpdateSubscribeCallback) {
        appSync.subscribeToRideUpdates(cb)
    }

    fun getRideDPs(rideID: String, cb: AppSyncTest.GetRideDPsCallback) {
        appSync.getRideDPs(rideID, cb)
    }
}