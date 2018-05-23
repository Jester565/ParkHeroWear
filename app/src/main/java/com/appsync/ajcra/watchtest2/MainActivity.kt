package com.appsync.ajcra.watchtest2

import android.arch.persistence.room.Database
import android.arch.persistence.room.Room
import android.os.Bundle
import android.support.v7.widget.RecyclerView
import android.support.wear.widget.WearableLinearLayoutManager
import android.support.wear.widget.WearableRecyclerView
import android.support.wearable.activity.WearableActivity
import android.util.Log
import android.view.View
import com.dis.ajcra.fastpass.fragment.DisRide
import com.dis.ajcra.fastpass.fragment.DisRideTime
import com.dis.ajcra.fastpass.fragment.DisRideUpdate
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter


class MainActivity : WearableActivity() {
    private lateinit var cognitoManager: CognitoManager
    private lateinit var cfm: CloudFileManager
    private lateinit var recyclerView: WearableRecyclerView
    private lateinit var recyclerViewAdapter: RideRecyclerAdapter
    private lateinit var rideManager: RideManager
    private lateinit var pinDb: PinDatabase
    private var ridesInitialized = false
    private var disRideUpdates = ArrayList<DisRideUpdate>()
    private var pinnedRides = ArrayList<Ride>()
    private var rides = ArrayList<Ride>()
    private var dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    fun initRides(disRides: List<DisRide>) = async(UI) {
        disRides.sortedBy { it->
            it.info()!!.name()
        }
        for (disRide in disRides!!) {
            try {
                var getPinJob = async {
                    pinDb.pinInfoDao().getPin(disRide.id()!!)
                }
                var pinInfo = getPinJob.await()
                if (pinInfo != null) {
                    pinnedRides.add(Ride(disRide))
                } else {
                    rides.add(Ride(disRide))
                }
            } catch (ex: Exception) {
                Log.d("STATE", "Ex: "+ ex)
            }
        }
        ridesInitialized = true
        for (rideUpdate in disRideUpdates) {
            updateRideTime(rideUpdate.id()!!, rideUpdate.time()!!.fragments().disRideTime())
        }
        recyclerViewAdapter.notifyDataSetChanged()
    }

    fun updateRideTime(id: String, rideTime: DisRideTime) = async(UI) {
        var getPinJob = async {
            pinDb.pinInfoDao().getPin(id)
        }
        var pinInfo = getPinJob.await()
        if (pinInfo != null) {
            var rideI = pinnedRides.indexOfFirst {it->
                it.id == id
            }
            if (rideI >= 0) {
                var ride = pinnedRides.get(rideI)
                /*
                var newDate = rideTime.dateTime()
                var newTimestamp = dateTimeFormat.parse(newDate).time
                var oldDate = ride.time?.dateTime()
                var oldTimestamp = dateTimeFormat.parse(oldDate).time
                if (newTimestamp >= oldTimestamp) {
                    ride?.setRideTime(rideTime)
                    recyclerViewAdapter.notifyItemChanged(rideI)
                }
                */
                ride?.setRideTime(rideTime)
                recyclerViewAdapter.notifyItemChanged(rideI)
            }
        } else {
            var rideI = pinnedRides.indexOfFirst {it->
                it.id == id
            }
            if (rideI >= 0) {
                var ride = rides.get(rideI)
                ride?.setRideTime(rideTime)
                recyclerViewAdapter.notifyItemChanged(rideI + pinnedRides.size)
            }
        }
    }

    fun getRides() {
        rideManager.getRides(object: AppSyncTest.GetRidesCallback {
            override fun onResponse(disRides: List<DisRide>) {
                async(UI) {
                    if (!ridesInitialized) {
                        initRides(disRides)
                    } else {
                        for (ride in disRides) {
                            updateRideTime(ride.id()!!, ride.time()!!.fragments().disRideTime())
                        }
                    }
                }
            }

            override fun onError(ec: Int?, msg: String?) {

            }
        })
    }

    /*
    fun subToRideUpdates() {
        rideManager.subscribeToRideUpdates(object: AppSyncTest.RideUpdateSubscribeCallback {
            override fun onFailure(e: Exception) {

            }

            override fun onUpdate(response: List<DisRideUpdate>) {
                async(UI) {
                    if (!ridesInitialized) {
                        disRideUpdates.addAll(response!!)
                    }
                    for (rideUpdate in disRideUpdates) {
                        updateRideTime(rideUpdate.id()!!, rideUpdate.time()!!.fragments().disRideTime())
                    }
                }
            }

            override fun onCompleted() {

            }

        })
    }
    */

    fun updateRides() {
        rideManager.getRideUpdates(object: AppSyncTest.UpdateRidesCallback {
            override fun onResponse(response: List<DisRideUpdate>?) {
                async(UI) {
                    if (!ridesInitialized) {
                        disRideUpdates.addAll(response!!)
                    }
                    for (rideUpdate in disRideUpdates) {
                        updateRideTime(rideUpdate.id()!!, rideUpdate.time()!!.fragments().disRideTime())
                    }
                }
            }

            override fun onError(ec: Int?, msg: String?) {

            }

        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cognitoManager = CognitoManager.GetInstance(applicationContext)
        cfm = CloudFileManager.GetInstance(cognitoManager, applicationContext)
        recyclerView = findViewById(R.id.main_rideRecycler)
        recyclerViewAdapter = RideRecyclerAdapter(rides, pinnedRides, cfm)
        rideManager = RideManager(applicationContext)
        pinDb = Room.databaseBuilder(applicationContext, PinDatabase::class.java, "pins").build()
        recyclerView.layoutManager = WearableLinearLayoutManager(this, object: WearableLinearLayoutManager.LayoutCallback() {
            private val MAX_ICON_PROGRESS = 0.65f

            private var mProgressToCenter: Float = 0f

            override fun onLayoutFinished(child: View?, parent: RecyclerView?) {
                val centerOffset = child!!.getHeight().toFloat() / 2.0f / parent!!.getHeight().toFloat()
                val yRelativeToCenterOffset = child.getY() / parent.getHeight() + centerOffset

                // Normalize for center
                mProgressToCenter = Math.abs(0.5f - yRelativeToCenterOffset)
                // Adjust to the maximum scale
                mProgressToCenter = Math.min(mProgressToCenter, MAX_ICON_PROGRESS)

                child.setScaleX(1 - mProgressToCenter)
                child.setScaleY(1 - mProgressToCenter)
            }

        })
        recyclerView.adapter = recyclerViewAdapter
        recyclerView.setItemViewCacheSize(200)
        recyclerView.isDrawingCacheEnabled = true
        recyclerView.isEdgeItemsCenteringEnabled  = true

        //recyclerView.adapter
        // Enables Always-on
        //subToRideUpdates()
        updateRides()
        getRides()
        setAmbientEnabled()
    }

    fun migratePinnedRides(pinInfoList: List<PinInfo>) {
        var pinnedRidesClone = pinnedRides.clone() as ArrayList<Ride>
        for (pinInfo in pinInfoList) {
            var rideI = pinnedRidesClone.indexOfFirst {
                pinInfo.id == it.id
            }
            if (rideI < 0) {
                rideI = rides.indexOfFirst {
                    pinInfo.id == it.id
                }
                var ride = rides.get(rideI)
                rides.removeAt(rideI)
                pinnedRides.add(ride!!)
            } else {
                pinnedRidesClone.removeAt(rideI)
            }
        }
        for (ride in pinnedRidesClone) {
            pinnedRides.removeIf {
                ride.id == it.id
            }
            rides.add(ride)
        }
        recyclerViewAdapter.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()
        if (ridesInitialized) {
            async(UI) {
                var listPinsJob = async {
                    pinDb.pinInfoDao().listPins()
                }
                var pinInfoList = listPinsJob.await()
                if (pinInfoList.size != pinnedRides.size) {
                    migratePinnedRides(pinInfoList)
                } else {
                    for (pinInfo in pinInfoList) {
                        var ride = pinnedRides.indexOfFirst {
                            pinInfo.id == it.id
                        }
                        if (ride == null) {
                            migratePinnedRides(pinInfoList)
                            break
                        }
                    }
                }
            }
        }
    }
}
