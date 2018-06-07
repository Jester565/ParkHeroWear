package com.appsync.ajcra.watchtest2

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.arch.persistence.room.Database
import android.arch.persistence.room.Room
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.support.v7.widget.RecyclerView
import android.support.wear.widget.WearableLinearLayoutManager
import android.support.wear.widget.WearableRecyclerView
import android.support.wearable.activity.WearableActivity
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import com.amazonaws.mobileconnectors.apigateway.ApiClientFactory
import com.appsync.ajcra.watchtest2.model.RideInfo
import com.appsync.ajcra.watchtest2.model.RideInfoInfo
import com.appsync.ajcra.watchtest2.model.RideInfoTime
import com.dis.ajcra.fastpass.GetRideDPsQuery
import com.dis.ajcra.fastpass.fragment.DisRide
import com.dis.ajcra.fastpass.fragment.DisRideTime
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter


class RideTimesActivity : WearableActivity() {
    private lateinit var cognitoManager: CognitoManager
    private lateinit var cfm: CloudFileManager
    private lateinit var recyclerView: WearableRecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerViewAdapter: RideRecyclerAdapter
    private lateinit var rideManager: RideManager
    private var pinnedRides = ArrayList<CRInfo>()
    private var rides = ArrayList<CRInfo>()
    private var clickedRideID: String? = null

    val rideClickHandler: (String) -> Unit = { rideID ->
        clickedRideID = rideID
        var intent = Intent(this, RideActivity::class.java)
        intent.putExtra("id", rideID)
        this.startActivity(intent)
    }

    fun getRides() {
        rideManager.listRides(object: RideManager.ListRidesCB {
            override fun onAllUpdated() {
                animateBackground()
            }

            override fun init(rideUpdates: ArrayList<CRInfo>) {
                if (rides.isEmpty() && pinnedRides.isEmpty()) {
                    for (ride in rideUpdates) {
                        if (ride.pinned) {
                            pinnedRides.add(ride)
                        } else {
                            rides.add(ride)
                        }
                    }
                    recyclerViewAdapter.notifyDataSetChanged()
                }
                progressBar.visibility = View.GONE
            }

            override fun onAdd(ride: CRInfo) {
                if (ride.pinned) {
                    var arrI = pinnedRides.binarySearch {
                        it.name.compareTo(ride.name)
                    }
                    var insertI = -(arrI + 1)
                    pinnedRides.add(insertI, ride)
                    recyclerViewAdapter.notifyItemInserted(insertI)
                } else {
                    var arrI = rides.binarySearch {
                        it.name.compareTo(ride.name)
                    }
                    var insertI = -(arrI + 1)
                    rides.add(insertI, ride)
                    recyclerViewAdapter.notifyItemInserted(insertI + pinnedRides.size)
                }
            }

            override fun onUpdate(ride: CRInfo) {
                if (ride.pinned) {
                    var arrI = pinnedRides.binarySearch {
                        it.name.compareTo(ride.name)
                    }
                    if (arrI < 0) {
                        Log.e("ERRRRRR", "RideI was less than 0 on onUpdate")
                        return
                    }
                    pinnedRides[arrI] = ride
                    recyclerViewAdapter.notifyItemChanged(arrI)
                } else {
                    var arrI = rides.binarySearch {
                        it.name.compareTo(ride.name)
                    }
                    if (arrI < 0) {
                        Log.e("ERRRRRR", "RideI was less than 0 on onUpdate")
                        return
                    }
                    rides[arrI] = ride
                    recyclerViewAdapter.notifyItemChanged(arrI + pinnedRides.size)
                }
            }
        })
    }

    fun animateBackground() {
        val colorFrom = resources.getColor(R.color.darkgreen, theme)
        val colorTo = resources.getColor(R.color.material_blue_grey_950, theme)
        val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
        colorAnimation.duration = 2000 // milliseconds
        colorAnimation.addUpdateListener { animator -> recyclerView.setBackgroundColor(animator.animatedValue as Int) }
        colorAnimation.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cognitoManager = CognitoManager.GetInstance(applicationContext)
        cfm = CloudFileManager.GetInstance(cognitoManager, applicationContext)
        rideManager = RideManager.GetInstance(applicationContext)
        recyclerView = findViewById(R.id.main_rideRecycler)
        progressBar = findViewById(R.id.main_progressBar)
        recyclerViewAdapter = RideRecyclerAdapter(rides, pinnedRides, cfm, rideClickHandler)

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
    }

    override fun onResume() {
        super.onResume()
        getRides()
        if (clickedRideID != null) {
            var crid = clickedRideID
            if (crid != null) {
                var ride = rideManager.getMemCachedRide(crid)
                if (ride != null) {
                    if (!ride.pinned) {
                        var arrI = pinnedRides.binarySearch {
                            it.name.compareTo(ride.name)
                        }
                        if (arrI < 0) {
                            Log.e("ERRRRRR", "RideI was less than 0 on onUpdate")
                            return
                        }
                        pinnedRides[arrI] = ride
                        recyclerViewAdapter.notifyItemChanged(arrI)
                    }
                }
            }
        }
    }
}
