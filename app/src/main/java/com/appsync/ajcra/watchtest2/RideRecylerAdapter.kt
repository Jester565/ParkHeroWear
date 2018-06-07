package com.appsync.ajcra.watchtest2

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import java.io.File

//should contain click listeners, can have different ViewHolders
class RideRecyclerAdapter: RecyclerView.Adapter<RideRecyclerAdapter.ViewHolder> {
    private var rides: ArrayList<CRInfo>
    private var pinnedRides: ArrayList<CRInfo>
    private var cfm: CloudFileManager
    private var rideClickHandler: (String)->Unit

    constructor(rides: ArrayList<CRInfo>, pinnedRides: ArrayList<CRInfo>, cfm: CloudFileManager, rideClickHandler: (String)->Unit) {
        this.rides = rides
        this.pinnedRides = pinnedRides
        this.cfm = cfm
        this.rideClickHandler = rideClickHandler
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        var view = LayoutInflater.from(parent?.context).inflate(R.layout.row_ride, parent, false)
        var viewHolder = ViewHolder(view)
        return viewHolder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        lateinit var ride: CRInfo
        if (position < pinnedRides.size) {
            ride = pinnedRides.get(position)
        } else {
            ride = rides.get(position - pinnedRides.size)
        }
        if (holder != null) {
            async(UI) {
                holder!!.rootView.setOnClickListener {
                    rideClickHandler(ride.id)
                }
                holder.nameView.text = ride.name
                var waitTime = ride.waitTime
                if (waitTime != null) {
                    holder.waitMinsView.visibility = View.VISIBLE
                    holder.waitMinsView.text = waitTime.toString()
                } else {
                    holder.waitMinsView.visibility = View.GONE
                }
                var waitRating = ride.waitRating?.toFloat()
                if (waitRating != null) {
                    if (waitRating < -10.0f) {
                        waitRating = -10.0f
                    } else if (waitRating > 10.0f) {
                        waitRating = 10.0f
                    }
                    var hsl = floatArrayOf((waitRating + 10.0f)/20.0f * 120.0f, 1.0f, 0.5f)
                    holder.imgView.borderColor = ColorUtils.HSLToColor(hsl)
                } else {
                    holder.imgView.borderColor = Color.BLACK
                }
            }

            var picUrl = ride.picURL
            //Log.d("STATE", "PICURL: " + ride.picURL)
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
                                Log.d("STATE", "IMAGE SET")
                            }
                        }
                    })
                }
            } else {
                holder.imgView.setImageResource(R.drawable.ic_cancel_black_24dp)
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