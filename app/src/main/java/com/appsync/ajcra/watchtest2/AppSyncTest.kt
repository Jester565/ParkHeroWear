package com.appsync.ajcra.watchtest2

import android.content.Context
import android.util.Log
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.amazonaws.mobileconnectors.appsync.AppSyncSubscriptionCall
import com.amazonaws.mobileconnectors.appsync.fetcher.AppSyncResponseFetchers
import com.amazonaws.regions.Regions
import com.apollographql.apollo.GraphQLCall
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.fetcher.ResponseFetcher
import com.dis.ajcra.fastpass.*
import com.dis.ajcra.fastpass.fragment.DisFastPassTransaction
import com.dis.ajcra.fastpass.fragment.DisPass
import com.dis.ajcra.fastpass.fragment.DisRide
import com.dis.ajcra.fastpass.fragment.DisRideDP
import org.json.JSONObject
import java.util.*

class AppSyncTest {
    companion object {
        private var instance: AppSyncTest? = null
        fun getInstance(ctx: Context): AppSyncTest {
            if (instance == null) {
                instance = AppSyncTest()
                instance!!.getClient(ctx)
            }
            return instance as AppSyncTest
        }
    }
    private var client: AWSAppSyncClient? = null

    fun parseRespErrs(response: Response<Any>): Pair<Int?, String>? {
        Log.d("STATE", "RESP ERRORS: " + response.operation().name())
        for (error in response.errors()) {
            var errorMsg = error.message()
            if (errorMsg != null) {
                Log.d("STATE", errorMsg)
                var colonIdx = errorMsg.indexOf(":")
                if (colonIdx >= 0) {
                    try {
                        return Pair(errorMsg.substring(0, colonIdx).toInt(), errorMsg)
                    } catch(ex: NumberFormatException) {
                        Log.d("STATE", "# form exception")
                    }
                } else {
                    Log.d("STATE", "No statusCode in error")
                }
                return Pair(null, errorMsg)
            }
        }
        return null
    }

    interface AddPassCallback {
        fun onResponse(response: DisPass)
        fun onError(ec: Int?, msg: String?)
    }

    fun addPass(passID: String, cb: AddPassCallback) {
        (client as AWSAppSyncClient).mutate(AddPassMutation.builder().passID(passID).build())
                .enqueue(object: GraphQLCall.Callback<AddPassMutation.Data>() {
                    override fun onFailure(e: ApolloException) {
                        Log.d("STATE", "ON FAILURE: " + e.message)
                    }

                    override fun onResponse(response: Response<AddPassMutation.Data>) {
                        Log.d("STATE", "ON RESP")
                        if (!response.hasErrors()) {
                            cb.onResponse(response.data()!!.addPass()!!.fragments().disPass())
                        } else {
                            var errRes = parseRespErrs(response as Response<Any>)
                            cb.onError(errRes?.first, errRes?.second)
                        }
                    }
                })
    }

    interface RemovePassCallback {
        fun onResponse(response: Boolean)
        fun onError(ec: Int?, msg: String?)
    }

    fun removePass(passID: String, cb: RemovePassCallback) {
        (client as AWSAppSyncClient).mutate(RemovePassMutation.builder().passID(passID).build())
                .enqueue(object: GraphQLCall.Callback<RemovePassMutation.Data>() {
                    override fun onFailure(e: ApolloException) {
                        Log.d("STATE", "ON FAILURE: " + e.message)
                    }

                    override fun onResponse(response: Response<RemovePassMutation.Data>) {
                        Log.d("STATE", "ON RESP")
                        if (!response.hasErrors()) {
                            cb.onResponse(true)
                        } else {
                            var errRes = parseRespErrs(response as Response<Any>)
                            cb.onError(errRes?.first, errRes?.second)
                        }
                    }
                })
    }

    interface ListPassesCallback {
        fun onResponse(response: List<ListPassesQuery.ListPass>)
        fun onError(ec: Int?, msg: String?)
    }

    fun listPasses(cb: ListPassesCallback, fetcher: ResponseFetcher = AppSyncResponseFetchers.CACHE_AND_NETWORK) {
        (client as AWSAppSyncClient).query(ListPassesQuery.builder().build())
                .responseFetcher(fetcher)
                .enqueue(object: GraphQLCall.Callback<ListPassesQuery.Data>() {
                    override fun onFailure(e: ApolloException) {
                        Log.d("STATE", "ON FAILURE: " + e.message)
                    }

                    override fun onResponse(response: Response<ListPassesQuery.Data>) {
                        Log.d("STATE", "ON RESP")
                        if (!response.hasErrors()) {
                            var userPasses = response.data()!!.listPasses()!!
                            cb.onResponse(userPasses)
                        } else {
                            var errRes = parseRespErrs(response as Response<Any>)
                            cb.onError(errRes?.first, errRes?.second)
                        }
                    }
                })
    }

    interface AddFastPassCallback {
        fun onResponse(response: DisFastPassTransaction)
        fun onError(ec: Int?, msg: String?)
    }

    fun addFastPass(rideID: String, targetPassIDs: List<String>, cb: AddFastPassCallback) {
        (client as AWSAppSyncClient).mutate(AddFastPassMutation.builder()
                .rideID(rideID)
                .targetPasses(targetPassIDs)
                .build()).enqueue(object: GraphQLCall.Callback<AddFastPassMutation.Data>() {
            override fun onFailure(e: ApolloException) {
                Log.d("STATE", "ON FAILURE: " + e.message)
            }

            override fun onResponse(response: Response<AddFastPassMutation.Data>) {
                Log.d("STATE", "ON RESP")
                if (!response.hasErrors()) {
                    cb.onResponse(response.data()!!.addFastPass()!!.fragments().disFastPassTransaction())
                } else {
                    var errRes = parseRespErrs(response as Response<Any>)
                    cb.onError(errRes?.first, errRes?.second)
                }
            }
        })
    }

    interface ListFastPassesCallback {
        fun onResponse(response: List<DisFastPassTransaction>)
        fun onError(ec: Int?, msg: String?)
    }

    fun listFastPasses(cb: ListFastPassesCallback, fetcher: ResponseFetcher = AppSyncResponseFetchers.CACHE_AND_NETWORK) {
        (client as AWSAppSyncClient).query(ListFastPassesQuery.builder().build())
                .responseFetcher(fetcher)
                .enqueue(object: GraphQLCall.Callback<ListFastPassesQuery.Data>() {
                    override fun onFailure(e: ApolloException) {
                        Log.d("STATE", "ON FAILURE: " + e.message)
                    }

                    override fun onResponse(response: Response<ListFastPassesQuery.Data>) {
                        Log.d("STATE", "ON RESP")
                        if (!response.hasErrors()) {
                            var disFastPasses = ArrayList<DisFastPassTransaction>()
                            var fastPasses = response.data()!!.listFastPasses()!!
                            for (fastPass in fastPasses) {
                                disFastPasses.add(fastPass.fragments().disFastPassTransaction())
                            }
                            cb.onResponse(disFastPasses)
                        } else {
                            var errRes = parseRespErrs(response as Response<Any>)
                            cb.onError(errRes?.first, errRes?.second)
                        }
                    }
                })
    }

    interface UpdateFastPassesCallback {
        fun onResponse(response: List<DisFastPassTransaction>, nextSelectionTime: String?)
        fun onError(ec: Int?, msg: String?)
    }

    fun updateFastPasses(cb: UpdateFastPassesCallback, fetcher: ResponseFetcher = AppSyncResponseFetchers.CACHE_AND_NETWORK) {
        (client as AWSAppSyncClient).mutate(UpdateFastPassesMutation.builder().build())
                .enqueue(object: GraphQLCall.Callback<UpdateFastPassesMutation.Data>() {
                    override fun onFailure(e: ApolloException) {
                        Log.d("STATE", "ON FAILURE: " + e.message)
                    }

                    override fun onResponse(response: Response<UpdateFastPassesMutation.Data>) {
                        Log.d("STATE", "ON RESP")
                        if (!response.hasErrors()) {
                            var disFastPasses = ArrayList<DisFastPassTransaction>()
                            if (response.data()?.updateFastPasses()?.fps() != null) {
                                var fastPasses = response.data()!!.updateFastPasses()!!.fps()!!
                                for (fastPass in fastPasses) {
                                    disFastPasses.add(fastPass.fragments().disFastPassTransaction())
                                }
                            }
                            cb.onResponse(disFastPasses, response.data()?.updateFastPasses()?.nextSelection())
                        } else {
                            var errRes = parseRespErrs(response as Response<Any>)
                            cb.onError(errRes?.first, errRes?.second)
                        }
                    }
                })
    }

    interface GetRidesCallback {
        fun onResponse(response: List<DisRide>)
        fun onError(ec: Int?, msg: String?)
    }

    fun getRides(cb: GetRidesCallback, fetcher: ResponseFetcher = AppSyncResponseFetchers.NETWORK_ONLY) {
        (client as AWSAppSyncClient).query(GetRidesQuery.builder().build())
                .responseFetcher(fetcher)
                .enqueue(object: GraphQLCall.Callback<GetRidesQuery.Data>() {
                    override fun onFailure(e: ApolloException) {
                        Log.d("STATE", "ON FAILURE: " + e)
                    }

                    override fun onResponse(response: Response<GetRidesQuery.Data>) {
                        Log.d("STATE", "ON GET RIDES RESP")
                        if (!response.hasErrors()) {
                            Log.d("STATE", "GetRidesQuery RESP: ")
                            var rides = response.data()!!.rides!!
                            var disRides = ArrayList<DisRide>()
                            for (ride in rides) {
                                var disRide = ride.fragments().disRide()
                                disRides.add(disRide)
                            }
                            cb.onResponse(disRides)
                        } else {
                            var errRes = parseRespErrs(response as Response<Any>)
                            cb.onError(errRes?.first, errRes?.second)
                        }
                    }
                })
    }

    interface UpdateRidesCallback {
        fun onResponse(response: List<DisRide>?)
        fun onError(ec: Int?, msg: String?)
    }

    fun updateRides(cb: UpdateRidesCallback) {
        Log.d("STATE", "Running updating rides")
        try {
            (client as AWSAppSyncClient).mutate(UpdateRidesMutation.builder().build())
                    .enqueue(object : GraphQLCall.Callback<UpdateRidesMutation.Data>() {
                        override fun onFailure(e: ApolloException) {
                            Log.d("STATE", "ON FAILURE: " + e.message)
                        }

                        override fun onResponse(response: Response<UpdateRidesMutation.Data>) {
                            Log.d("STATE", "ON RESP")
                            if (!response.hasErrors()) {
                                Log.d("STATE", "UpdateRidesMutations RESP: ")
                                var disRideUpdates = ArrayList<DisRide>()
                                var ridesUpdatedContainer = response.data()!!.updateRides()
                                if (ridesUpdatedContainer != null && ridesUpdatedContainer.rides() != null) {
                                    for (rideUpdate in ridesUpdatedContainer.rides()!!) {
                                        var disRideUpdate = rideUpdate.fragments().disRide()
                                        Log.d("STATE", "RideID: " + disRideUpdate.id())
                                        disRideUpdates.add(disRideUpdate)
                                    }
                                    cb.onResponse(disRideUpdates)
                                } else {
                                    cb.onResponse(null)
                                }
                            } else {
                                var errRes = parseRespErrs(response as Response<Any>)
                                cb.onError(errRes?.first, errRes?.second)
                            }
                        }
                    })
        } catch (ex: Exception) {
            Log.d("STATE","BRAKAKA " + ex.message)
        }
    }

    interface RideUpdateSubscribeCallback {
        fun onFailure(e: Exception)
        fun onUpdate(rideUpdates: List<DisRide>)
        fun onCompleted()
    }

    fun subscribeToRideUpdates(cb: RideUpdateSubscribeCallback): AppSyncSubscriptionCall<RidesUpdatedSubscription.Data> {
        var subscription = (client as AWSAppSyncClient).subscribe(RidesUpdatedSubscription.builder().build())
        subscription.execute(object: AppSyncSubscriptionCall.Callback<RidesUpdatedSubscription.Data> {
            override fun onFailure(e: ApolloException) {
                Log.d("STATE", "RidedUpdatedSubscription ERR: " + e.message)
                cb.onFailure(e)
            }

            override fun onResponse(response: Response<RidesUpdatedSubscription.Data>) {
                Log.d("STATE", "RideUpdatedSubscription RESP: ")
                if (!response.hasErrors()) {
                    var ridesUpdatedContainer = response.data()!!.ridesUpdated()
                    var disRideUpdates = ArrayList<DisRide>()
                    if (ridesUpdatedContainer != null) {
                        for (rideUpdate in ridesUpdatedContainer.rides()!!) {
                            var disRideUpdate = rideUpdate.fragments().disRide()
                            Log.d("STATE", "RideID: " + disRideUpdate.id())
                            disRideUpdates.add(disRideUpdate)
                        }
                    }
                    cb.onUpdate(disRideUpdates)
                } else {
                    parseRespErrs(response as Response<Any>)
                }
            }

            override fun onCompleted() {
                Log.d("STATE", "COMPLETED")
                onCompleted()
            }
        })
        return subscription
    }


    class DisRideDPs {
        var rideDPs: ArrayList<DisRideDP> = ArrayList<DisRideDP>()
        var predictedDPs: ArrayList<DisRideDP> = ArrayList<DisRideDP>()
    }

    interface GetRideDPsCallback {
        fun onResponse(response: DisRideDPs?)
        fun onError(ec: Int?, msg: String?)
    }

    fun getRideDPs(rideID: String, cb: GetRideDPsCallback, fetcher: ResponseFetcher = AppSyncResponseFetchers.NETWORK_ONLY) {
        (client as AWSAppSyncClient).query(GetRideDPsQuery.builder().rideID(rideID).build())
                .responseFetcher(fetcher)
                .enqueue(object: GraphQLCall.Callback<GetRideDPsQuery.Data>() {
                    override fun onFailure(e: ApolloException) {
                        Log.d("STATE", "ON FAILURE: " + e.message)
                        cb.onError(null, e.message)
                    }

                    override fun onResponse(response: Response<GetRideDPsQuery.Data>) {
                        Log.d("STATE", "ON GET DPS RESP")
                        if (!response.hasErrors()) {
                            if (response.data() != null && response.data()?.rideDPs != null) {
                                Log.d("STATE", "GetRideDPS RESP: ")
                                var dpResp = response.data()!!.rideDPs!!
                                var disRideDPs = DisRideDPs()
                                for (dp in dpResp.rideTimes()!!) {
                                    disRideDPs.rideDPs.add(dp.fragments().disRideDP())
                                }
                                for (dp in dpResp.predictTimes()!!) {
                                    disRideDPs.predictedDPs.add(dp.fragments().disRideDP())
                                }
                                cb.onResponse(disRideDPs)
                            } else {
                                cb.onResponse(null)
                            }
                        } else {
                            parseRespErrs(response as Response<Any>)
                        }
                    }
                })
    }

    interface GetSchedulesCallback {
        fun onResponse(response: List<GetSchedulesQuery.Schedule>)
        fun onError(ec: Int?, msg: String?)
    }

    fun getSchedules(cb: GetSchedulesCallback, fetcher: ResponseFetcher = AppSyncResponseFetchers.NETWORK_ONLY) {
        (client as AWSAppSyncClient).query(GetSchedulesQuery.builder().build())
                .responseFetcher(fetcher)
                .enqueue(object: GraphQLCall.Callback<GetSchedulesQuery.Data>() {
                    override fun onFailure(e: ApolloException) {
                        Log.d("STATE", "ON FAILURE: " + e.message)
                        cb.onError(null, e.message)
                    }

                    override fun onResponse(response: Response<GetSchedulesQuery.Data>) {
                        if (!response.hasErrors()) {
                            cb.onResponse(response.data()!!.schedules?.schedules()!!)
                        } else {
                            var parsedErr = parseRespErrs(response as Response<Any>)
                            cb.onError(parsedErr?.first, parsedErr?.second)
                        }
                    }
                })
    }

    interface GetHourlyWeatherCallback {
        fun onResponse(response: List<GetHourlyWeatherQuery.Weather>)
        fun onError(ec: Int?, msg: String?)
    }

    fun getHourlyWeather(dateStr: String, cb: GetHourlyWeatherCallback, fetcher: ResponseFetcher = AppSyncResponseFetchers.NETWORK_ONLY) {
        (client as AWSAppSyncClient).query(GetHourlyWeatherQuery.builder().date(dateStr).build())
                .responseFetcher(fetcher)
                .enqueue(object: GraphQLCall.Callback<GetHourlyWeatherQuery.Data>() {
                    override fun onFailure(e: ApolloException) {
                        Log.d("STATE", "ON FAILURE: " + e.message)
                        cb.onError(null, e.message)
                    }

                    override fun onResponse(response: Response<GetHourlyWeatherQuery.Data>) {
                        if (!response.hasErrors()) {
                            cb.onResponse(response.data()!!.hourlyWeather!!.weather()!!)
                        } else {
                            var parsedErr = parseRespErrs(response as Response<Any>)
                            cb.onError(parsedErr?.first, parsedErr?.second)
                        }
                    }
                })
    }

    fun getClient(ctx: Context) {
        var cognitoManager = CognitoManager.GetInstance(ctx)
        if (client == null) {
            var appSyncConfigIn = ctx.resources.openRawResource(R.raw.appsync)
            var appSyncConfigStr = appSyncConfigIn.bufferedReader().readText()
            var appSyncConfig = JSONObject(appSyncConfigStr)
            client = AWSAppSyncClient.builder()
                    .context(ctx)
                    .credentialsProvider(cognitoManager.credentialsProvider)
                    .region(Regions.fromName(appSyncConfig.getString("region")))
                    .serverUrl(appSyncConfig.getString("graphqlEndpoint"))
                    .build()
        }
    }
}
