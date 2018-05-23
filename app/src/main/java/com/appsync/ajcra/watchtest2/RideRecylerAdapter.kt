package com.appsync.ajcra.watchtest2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.support.v4.graphics.ColorUtils
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.dis.ajcra.fastpass.fragment.DisRideUpdate
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import java.io.File

//should contain click listeners, can have different ViewHolders
class RideRecyclerAdapter: RecyclerView.Adapter<RideRecyclerAdapter.ViewHolder> {
    private var rides: ArrayList<Ride>
    private var pinnedRides: ArrayList<Ride>
    private var cfm: CloudFileManager

    constructor(rides: ArrayList<Ride>, pinnedRides: ArrayList<Ride>, cfm: CloudFileManager) {
        this.rides = rides
        this.pinnedRides = pinnedRides
        this.cfm = cfm
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        var view = LayoutInflater.from(parent?.context).inflate(R.layout.row_ride, parent, false)
        var viewHolder = ViewHolder(view)
        return viewHolder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        lateinit var ride: Ride
        if (position < pinnedRides.size) {
            ride = pinnedRides.get(position)
        } else {
            ride = rides.get(position - pinnedRides.size)
        }
        if (holder != null) {
            async(UI) {
                holder!!.rootView.setOnClickListener {
                    var rideID = ride.id
                    var intent = Intent(holder!!.ctx, RideActivity::class.java)
                    intent.putExtra("id", rideID)
                    holder!!.ctx.startActivity(intent)
                }
                holder.nameView.text = ride.info.name()!!
                var waitTime = ride.time?.waitTime()
                if (waitTime != null) {
                    holder.waitMinsView.visibility = View.VISIBLE
                    holder.waitMinsView.text = waitTime.toString()
                }
                var waitRating = ride.time?.waitRating()?.toFloat()
                if (waitRating != null) {
                    if (waitRating < -10.0f) {
                        waitRating = -10.0f
                    } else if (waitRating > 10.0f) {
                        waitRating = 10.0f
                    }
                    var hsl = floatArrayOf((waitRating + 10.0f)/20.0f * 120.0f, 1.0f, 0.5f)
                    holder.imgView.borderColor = ColorUtils.HSLToColor(hsl)
                }
            }

            var picUrl = ride.info.picUrl()
            if (picUrl != null && holder.imgKey != picUrl)
            {
                holder.imgKey = picUrl
                async {
                    picUrl = picUrl?.substring(0, picUrl?.length!! - 4) + "-0" + picUrl?.substring(picUrl?.length!! - 4)
                    Log.d("STATE", "PICURL: " + picUrl)
                    cfm.download(picUrl.toString(), object : CloudFileListener() {
                        override fun onError(id: Int, ex: Exception?) {
                            Log.d("STATE", "RidePicUrlErr: " + ex?.message)
                        }

                        override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                            //Log.d("STATE", "ProgressChanged")
                        }

                        override fun onStateChanged(id: Int, state: TransferState?) {
                            //Log.d("STATE", " STATE CHANGE: " + state.toString())
                        }

                        override fun onComplete(id: Int, file: File) {
                            Log.d("STATE", "Ride download complete")
                            async(UI) {
                                holder.imgView.setImageURI(Uri.fromFile(file))
                            }
                        }
                    })
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return pinnedRides.size + rides.size
    }

    class ViewHolder : RecyclerView.ViewHolder {
        var imgView: CircleImageView
        var nameView: TextView
        var waitMinsView: TextView
        var rootView: View
        var ctx: Context
        var imgKey: String? = null

        constructor(itemView: View)
                : super(itemView) {
            ctx = itemView.context
            rootView = itemView
            imgView = rootView.findViewById(R.id.rowride_img)
            nameView = rootView.findViewById(R.id.rowride_name)
            waitMinsView = rootView.findViewById(R.id.rowride_waitMins)
        }
    }
}