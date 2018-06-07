package com.appsync.ajcra.watchtest2

import android.arch.persistence.room.Room
import android.content.res.Resources
import android.graphics.Color
import android.net.Uri
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.graphics.ColorUtils
import android.support.wearable.activity.WearableActivity
import android.util.Log
import android.view.View
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.dis.ajcra.fastpass.fragment.DisRide
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import org.w3c.dom.Text
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.ColorFilter
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.*
import com.dis.ajcra.fastpass.fragment.DisRideTime


class RideActivity : WearableActivity() {
    companion object {
        private const val FACTOR = 0.146467f
        private const val PIN_CHANGE = 10f
    }

    private lateinit var cognitoManager: CognitoManager
    private lateinit var cfm: CloudFileManager
    private lateinit var rideManager: RideManager

    private lateinit var infoLayout: LinearLayout
    private lateinit var imgView: CircleImageView
    private lateinit var nameView: TextView
    private lateinit var waitMinsView: TextView
    private lateinit var fpView1: TextView
    private lateinit var fpView2: TextView
    private lateinit var rideStatusView: TextView
    private lateinit var fpStatusView: TextView
    private lateinit var fpLayout: RelativeLayout
    private lateinit var waitLayout: RelativeLayout
    private lateinit var pinButton: ImageButton
    private var fpParseFormat = SimpleDateFormat("HH:mm:ss")
    private var dateDispFormat = SimpleDateFormat("h:mm a")
    private var infoSet = false
    private var pinned = false
    lateinit private var rideID: String
    private var lrcb: RideManager.ListRidesCB? = null

    private fun adjustInset() {
        if (applicationContext.resources.configuration.isScreenRound) {
            val inset = (FACTOR * Resources.getSystem().displayMetrics.widthPixels).toInt()
            infoLayout.setPadding(inset, inset, inset, inset)
        }
    }

    fun getImgObjKey(objKey: String, qualityRating: Int): String {
        return objKey.substring(0, objKey.length!! - 4) + "-" + qualityRating.toString() + objKey.substring(objKey.length!! - 4)
    }

    fun setImg(objKey: String) = async {
        var objKeySized = getImgObjKey(objKey, 2)
        cfm.download(objKeySized, object: CloudFileListener() {
            override fun onError(id: Int, ex: Exception?) {

            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {

            }

            override fun onStateChanged(id: Int, state: TransferState?) {

            }

            override fun onComplete(id: Int, file: File) {
                async(UI) {
                    imgView.setImageURI(Uri.fromFile(file))
                    val cf = PorterDuffColorFilter(Color.argb(180, 0, 0, 0), PorterDuff.Mode.DARKEN)
                    imgView.setColorFilter(cf)
                }
            }
        })
    }

    fun updateRide(ride: CRInfo) {
        //var rideInfo = ride.info()
        if (!infoSet) {
            nameView.text = ride.name
            var picKey = ride.picURL
            if (picKey != null) {
                setImg(picKey)
            }
            bindPinButton(ride.pinned)
        }
        //var rideTime = ride.time()?.fragments()?.disRideTime()
        if (ride.waitTime != null) {
            waitMinsView.text = ride.waitTime.toString()
            rideStatusView.visibility = View.GONE
            waitLayout.visibility = View.VISIBLE
        } else {
            rideStatusView.text = ride.status
        }
        var fpTimeStr = ride.fpTime
        if (fpTimeStr != null) {
            var date = fpParseFormat.parse(fpTimeStr)
            var cal = GregorianCalendar.getInstance()
            cal.time = date
            fpView1.text = dateDispFormat.format(date)
            cal.add(Calendar.HOUR, 1)
            fpView2.text = dateDispFormat.format(cal.time)
            fpStatusView.visibility = View.GONE
            fpLayout.visibility = View.VISIBLE
        } else {
            fpStatusView.text = "No More FastPasses"
        }
        var waitRating = ride.waitRating?.toFloat()
        if (waitRating != null) {
            if (waitRating < -10.0f) {
                waitRating = -10.0f
            } else if (waitRating > 10.0f) {
                waitRating = 10.0f
            }
            var hsl = floatArrayOf((waitRating + 10.0f)/20.0f * 120.0f, 1.0f, 0.5f)
            imgView.borderColor = ColorUtils.HSLToColor(hsl)
        }
    }

    fun setPinButton(initialSet: Boolean, pinned: Boolean) = async(UI) {
        if (pinned) {
            pinButton.setColorFilter(Color.argb(200, 0, 0, 0))
            if (!initialSet) {
                pinButton.animate().scaleX(0.5f).scaleY(0.5f).setDuration(60).setListener(null)
            } else {
                pinButton.scaleX = 0.5f
                pinButton.scaleY = 0.5f
            }
        } else {
            pinButton.clearColorFilter()
            if (!initialSet) {
                pinButton.animate().scaleX(1.0f).scaleY(1.0f).setDuration(60).setListener(null)
            } else {

            }
        }
    }

    fun bindPinButton(pinned: Boolean) = async(UI) {
        this@RideActivity.pinned = pinned
        setPinButton(true, pinned)
        pinButton.setOnClickListener { view ->
            async {
                this@RideActivity.pinned = !this@RideActivity.pinned
                //ride.pinned = !ride.pinned
                try {
                    //crDb.crInfoDao().addCRInfo(ride)
                    rideManager.updatePinned(rideID, this@RideActivity.pinned)
                } catch (ex: Exception) {
                    Log.d("STATE", "PIN ERROR: " + ex.message)
                }
                setPinButton(false, this@RideActivity.pinned)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ride)
        rideID = intent.extras.getString("id")
        cognitoManager = CognitoManager.GetInstance(applicationContext)
        cfm = CloudFileManager(cognitoManager, applicationContext)
        rideManager = RideManager.GetInstance(applicationContext)

        getRide()

        infoLayout = findViewById(R.id.ride_mainLayout)
        imgView = findViewById(R.id.ride_img)
        nameView = findViewById(R.id.ride_name)
        waitMinsView = findViewById(R.id.ride_waitMins)
        fpView1 = findViewById(R.id.ride_fpText1)
        fpView2 = findViewById(R.id.ride_fpText2)
        rideStatusView = findViewById(R.id.ride_status)
        fpStatusView = findViewById(R.id.ride_fpStatus)
        fpLayout = findViewById(R.id.ride_fpLayout)
        waitLayout = findViewById(R.id.ride_waitLayout)
        pinButton = findViewById(R.id.ride_pinButton)
        adjustInset()
    }

    fun getRide() {
        async(UI) {
            lrcb = rideManager.getRide(rideID, object : RideManager.GetRideCB {
                override fun onUpdate(ride: CRInfo) {
                    updateRide(ride)
                }

                override fun onFinalUpdate(ride: CRInfo) {
                    //setRide(ride)
                }
            })
        }
    }

    override fun onStop() {
        super.onStop()
        var cb = lrcb
        if (cb != null) {
            rideManager.unsubscribe(cb)
        }
    }
}
