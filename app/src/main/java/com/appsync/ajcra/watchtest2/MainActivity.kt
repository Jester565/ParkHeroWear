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
import com.dis.ajcra.fastpass.GetRideDPsQuery
import com.dis.ajcra.fastpass.fragment.DisRide
import com.dis.ajcra.fastpass.fragment.DisRideTime
import kotlinx.coroutines.experimental.Deferred
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
    private lateinit var crDb: RideCacheDatabase
    private var disRideUpdates = ArrayList<DisRide>()
    private var pinnedRides = ArrayList<CRInfo>()
    private var rides = ArrayList<CRInfo>()
    private var dateTimeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

    fun initRide(crInfo: CRInfo, rideID: String, rideInfo: DisRide.Info, rideTime: DisRideTime?) {
        crInfo.id = rideID
        crInfo.name = rideInfo.name()!!
        crInfo.picURL = rideInfo.picUrl()
        crInfo.land = rideInfo.land()
        crInfo.height = rideInfo.height()
        if (rideTime != null) {
            setRideTime(crInfo, rideTime)
        }
    }

    fun setRideTime(crInfo: CRInfo, rideTime: DisRideTime) {
        Log.d("MON", "DTStr: " + rideTime.changedTime())
        Log.d("MON", "DT: " + dateTimeFormat.parse(rideTime.changedTime()).time.toString())
        var timestamp = dateTimeFormat.parse(rideTime.dateTime()).time
        var previousTimestamp = crInfo.lastChangeTime
        if (previousTimestamp == null || previousTimestamp < timestamp) {
            crInfo.waitRating = rideTime.waitRating()
            crInfo.waitTime = rideTime.waitTime()
            crInfo.fpTime = rideTime.fastPassTime()
            crInfo.status = rideTime.status()
            crInfo.lastChangeTime = timestamp
        }
    }

    fun getCachedRides() = async(UI) {
        Log.d("MON", "CACHED RIDE START")
        async {
            try {
                if (rides.isEmpty()) {
                    rides.addAll(crDb.crInfoDao().listCacheRideOfPin(false))
                } else {}
                if (pinnedRides.isEmpty()) {
                    pinnedRides.addAll(crDb.crInfoDao().listCacheRideOfPin(true))
                } else {}
            } catch (ex: Exception) {
                Log.d("STATE", ex.message)
            }
        }.await()
        recyclerViewAdapter.notifyDataSetChanged()
        Log.d("MON", "CACHED RIDE END")
    }

    fun updateInsertRideTime(rideID: String, rideInfo: DisRide.Info, rideTime: DisRideTime?): Deferred<Pair<Boolean, CRInfo>> = async(UI) {
        var inserted = false
        var ride: CRInfo = CRInfo()
        var name = rideInfo.name()
        var rideI = rides.binarySearch {
            String.CASE_INSENSITIVE_ORDER.compare(it.name, name)
        }
        if (rideTime != null) {
            if (rideI >= 0) {
                ride = rides.get(rideI)
                setRideTime(ride, rideTime)
                recyclerViewAdapter.notifyItemChanged(rideI + pinnedRides.size)
            } else {
                var pinnedI = pinnedRides.binarySearch { it ->
                    String.CASE_INSENSITIVE_ORDER.compare(it.name, name)
                }
                if (pinnedI >= 0) {
                    ride = pinnedRides.get(pinnedI)
                    setRideTime(ride, rideTime)
                    recyclerViewAdapter.notifyItemChanged(pinnedI)
                } else {
                    var actualInsertionPoint = -(rideI + 1)
                    ride = CRInfo()
                    initRide(ride, rideID, rideInfo, rideTime)
                    rides.add(actualInsertionPoint, ride)
                    inserted = true
                }
            }
        } else {
            var pinnedI = pinnedRides.binarySearch { it ->
                String.CASE_INSENSITIVE_ORDER.compare(it.name, name)
            }
            if (rideI < 0 && pinnedI < 0) {
                var actualInsertionPoint = -(rideI + 1)
                ride = CRInfo()
                initRide(ride, rideID, rideInfo, rideTime)
                rides.add(actualInsertionPoint, ride)
                inserted = true
            }
        }
        Pair(inserted, ride)
    }

    fun getRides() {
        Log.d("MON", "Get RIDE START")
        rideManager.getRides(object: AppSyncTest.GetRidesCallback {
            override fun onResponse(disRides: List<DisRide>) {
                Log.d("MON", "Hello?")
                async(UI) {
                    var arr = ArrayList<CRInfo>()
                    var sizeChanged = false
                    for (ride in disRides) {
                        var result = updateInsertRideTime(ride.id()!!, ride.info()!!, ride.time()?.fragments()?.disRideTime()).await()
                        if (!sizeChanged) {
                            sizeChanged = result.first
                        }
                        arr.add(result.second)
                    }
                    //if (sizeChanged) {
                        recyclerViewAdapter.notifyDataSetChanged()
                    //}
                    Log.d("MON", "Get RIDE END")
                    async {
                        for (crInfo in arr) {
                            crDb.crInfoDao().addCRInfo(crInfo)
                        }
                    }
                }
            }

            override fun onError(ec: Int?, msg: String?) {

            }
        })
    }

    fun updateRides() {
        Log.d("MON", "Update RIDE START")
        rideManager.getRideUpdates(object: AppSyncTest.UpdateRidesCallback {
            override fun onResponse(response: List<DisRide>?) {
                for (ride in disRideUpdates) {
                    updateInsertRideTime(ride.id()!!, ride.info()!!, ride.time()?.fragments()?.disRideTime())
                }
                Log.d("MON", "Update RIDE End")
            }

            override fun onError(ec: Int?, msg: String?) {

            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MON", "ON CREATE START")
        setContentView(R.layout.activity_main)
        cognitoManager = CognitoManager.GetInstance(applicationContext)
        Log.d("MON", "CFM START")
        cfm = CloudFileManager.GetInstance(cognitoManager, applicationContext)
        Log.d("MON", "CFM END")
        recyclerView = findViewById(R.id.main_rideRecycler)
        recyclerViewAdapter = RideRecyclerAdapter(rides, pinnedRides, cfm)
        Log.d("MON", "R START")
        rideManager = RideManager(applicationContext)
        Log.d("MON", "R END")
        Log.d("MON", "D START")
        crDb = Room.databaseBuilder(applicationContext, RideCacheDatabase::class.java, "rides3").build()
        Log.d("MON", "D END")
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
        getCachedRides()

        setAmbientEnabled()
    }

    /*
    fun migratePinnedRides(pinInfoList: List<CRI>) {
        var pinnedRidesClone = pinnedRides.clone() as ArrayList<CRInfo>
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
    */

    override fun onResume() {
        super.onResume()
        Log.d("MON", "CALLING")
        updateRides()
        //getCachedRides()
        getRides()
        Log.d("MON", "CALLING END")
        /*
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
        */
    }
}
