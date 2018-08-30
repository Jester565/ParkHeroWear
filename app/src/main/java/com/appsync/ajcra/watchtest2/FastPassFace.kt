package com.appsync.ajcra.watchtest2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.v7.graphics.Palette
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.text.TextPaint
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import com.amazonaws.mobileconnectors.appsync.fetcher.AppSyncResponseFetchers
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.dis.ajcra.fastpass.fragment.DisFastPassTransaction
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import java.io.File

import java.lang.ref.WeakReference
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 1000

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0

private const val HOUR_STROKE_WIDTH = 5f
private const val MINUTE_STROKE_WIDTH = 3f
private const val SECOND_TICK_STROKE_WIDTH = 2f

private const val CENTER_GAP_AND_CIRCLE_RADIUS = 4f

private const val SHADOW_RADIUS = 6f

class FastPassFace : CanvasWatchFaceService() {

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: FastPassFace.Engine) : Handler() {
        private val mWeakReference: WeakReference<FastPassFace.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = mWeakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }


    inner class FPPoint {
        var fpTime: Date
        var rideID: String
        var rideManager: RideManager
        var cfm: CloudFileManager
        var rideImgBmp: Bitmap? = null
        var rideName: String? = null

        constructor(rideManager: RideManager, cfm: CloudFileManager, rideID: String, fpTime: Date) {
            this.rideManager = rideManager
            this.cfm = cfm
            this.rideID = rideID
            this.fpTime = fpTime
        }

        fun getImgObjKey(objKey: String, qualityRating: Int, circle: Boolean = false): String {
            var str = objKey.substring(0, objKey.length!! - 4) + "-" + qualityRating.toString()
            if (circle) {
                str += "-c"
            }
            str += objKey.substring(objKey.length!! - 4)
            return str
        }

        fun init() = async {
            var gotRide = false
            rideManager.getRide(rideID, object: RideManager.GetRideCB {
                override fun onUpdate(ride: CRInfo) {
                    async {
                        if (!gotRide) {
                            var picUrl = ride.picURL
                            gotRide = true
                            rideName = ride.name
                            if (picUrl != null) {
                                cfm.download(getImgObjKey(picUrl, 0, true), object : CloudFileListener() {
                                    override fun onError(id: Int, ex: Exception?) {}

                                    override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {}

                                    override fun onStateChanged(id: Int, state: TransferState?) {}

                                    override fun onComplete(id: Int, file: File) {
                                        async(UI) {
                                            rideImgBmp = BitmapFactory.decodeStream(file.inputStream())
                                        }
                                    }
                                })
                            }
                        }
                    }
                }

                override fun onFinalUpdate(ride: CRInfo) {}
            })
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine() {

        private lateinit var mCalendar: Calendar

        private var mRegisteredTimeZoneReceiver = false
        private var mMuteMode: Boolean = false
        private var mCenterX: Float = 0F
        private var mCenterY: Float = 0F

        private var mSecondHandLength: Float = 0F
        private var sMinuteHandLength: Float = 0F
        private var sHourHandLength: Float = 0F

        /* Colors for all hands (hour, minute, seconds, ticks) based on photo loaded. */
        private var mWatchHandColor: Int = 0
        private var mWatchHandHighlightColor: Int = 0
        private var mWatchHandShadowColor: Int = 0

        private lateinit var mHourPaint: Paint
        private lateinit var mMinutePaint: Paint
        private lateinit var mSecondPaint: Paint
        private lateinit var mTickAndCirclePaint: Paint

        private lateinit var mBackgroundPaint: Paint
        private lateinit var mBackgroundBitmap: Bitmap
        private lateinit var mGrayBackgroundBitmap: Bitmap

        private var mAmbient: Boolean = false
        private var mLowBitAmbient: Boolean = false
        private var mBurnInProtection: Boolean = false
        private var cognitoManager: CognitoManager = CognitoManager.GetInstance(applicationContext)
        private var rideManager: RideManager = RideManager(applicationContext)
        private var cfm: CloudFileManager = CloudFileManager.GetInstance(cognitoManager, applicationContext)
        private var appSync: AppSyncTest = AppSyncTest.getInstance(applicationContext)

        private var colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE)

        private var width: Float = 300f
        private var height: Float = 300f

        private var fpDateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        private var fpDateFormat2: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

        private var fpPoints = TreeMap<Long, ArrayList<FastPassFace.FPPoint>>()
        private var fpPointArr = ArrayList<FastPassFace.FPPoint>()

        private var calendar = Calendar.getInstance()

        private var tapCounter: Int = 0

        private var nextSelectionTime: Date? = null
        private var nextSelectionStr: String = "Loading.."

        var hourFormatter = SimpleDateFormat("h:mm a")

        /* Handler to update the time once a second in interactive mode. */
        private val mUpdateTimeHandler = EngineHandler(this)

        private val mTimeZoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mCalendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(WatchFaceStyle.Builder(this@FastPassFace)
                    .setAcceptsTapEvents(true)
                    .build())

            mCalendar = Calendar.getInstance()

            initializeBackground()
            initializeWatchFace()
        }

        private fun initializeBackground() {
            mBackgroundPaint = Paint().apply {
                color = Color.BLACK
            }
            mBackgroundBitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            var canvas = Canvas(mBackgroundBitmap)
            canvas.drawColor(Color.BLACK)

            Palette.from(mBackgroundBitmap).generate {
                it?.let {
                    mWatchHandHighlightColor = it.getVibrantColor(Color.RED)
                    mWatchHandColor = it.getLightVibrantColor(Color.WHITE)
                    mWatchHandShadowColor = it.getDarkMutedColor(Color.BLACK)
                    updateWatchHandStyle()
                }
            }
        }

        private fun initializeWatchFace() {
            /* Set defaults for colors */
            mWatchHandColor = Color.WHITE
            mWatchHandHighlightColor = Color.RED
            mWatchHandShadowColor = Color.BLACK

            mHourPaint = Paint().apply {
                color = mWatchHandColor
                strokeWidth = HOUR_STROKE_WIDTH
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(
                        SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor)
            }

            mMinutePaint = Paint().apply {
                color = mWatchHandColor
                strokeWidth = MINUTE_STROKE_WIDTH
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(
                        SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor)
            }

            mSecondPaint = Paint().apply {
                color = mWatchHandHighlightColor
                strokeWidth = SECOND_TICK_STROKE_WIDTH
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                setShadowLayer(
                        SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor)
            }

            mTickAndCirclePaint = Paint().apply {
                color = mWatchHandColor
                strokeWidth = SECOND_TICK_STROKE_WIDTH
                isAntiAlias = true
                style = Paint.Style.STROKE
                setShadowLayer(
                        SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor)
            }
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(
                    WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false)
            mBurnInProtection = properties.getBoolean(
                    WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false)
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            mAmbient = inAmbientMode

            updateWatchHandStyle()

            // Check and trigger whether or not timer should be running (only
            // in active mode).
            updateTimer()
        }

        private fun updateWatchHandStyle() {
            if (mAmbient) {
                mHourPaint.color = Color.WHITE
                mMinutePaint.color = Color.WHITE
                mSecondPaint.color = Color.WHITE
                mTickAndCirclePaint.color = Color.WHITE

                mHourPaint.isAntiAlias = false
                mMinutePaint.isAntiAlias = false
                mSecondPaint.isAntiAlias = false
                mTickAndCirclePaint.isAntiAlias = false

                mHourPaint.clearShadowLayer()
                mMinutePaint.clearShadowLayer()
                mSecondPaint.clearShadowLayer()
                mTickAndCirclePaint.clearShadowLayer()

            } else {
                mHourPaint.color = mWatchHandColor
                mMinutePaint.color = mWatchHandColor
                mSecondPaint.color = mWatchHandHighlightColor
                mTickAndCirclePaint.color = mWatchHandColor

                mHourPaint.isAntiAlias = true
                mMinutePaint.isAntiAlias = true
                mSecondPaint.isAntiAlias = true
                mTickAndCirclePaint.isAntiAlias = true

                mHourPaint.setShadowLayer(
                        SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor)
                mMinutePaint.setShadowLayer(
                        SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor)
                mSecondPaint.setShadowLayer(
                        SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor)
                mTickAndCirclePaint.setShadowLayer(
                        SHADOW_RADIUS, 0f, 0f, mWatchHandShadowColor)
            }
        }

        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)
            val inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode
                mHourPaint.alpha = if (inMuteMode) 100 else 255
                mMinutePaint.alpha = if (inMuteMode) 100 else 255
                mSecondPaint.alpha = if (inMuteMode) 80 else 255
                invalidate()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            this.width = width.toFloat()
            this.height = height.toFloat()

            mCenterX = width / 2f
            mCenterY = height / 2f

            /*
             * Calculate lengths of different hands based on watch screen size.
             */
            mSecondHandLength = (mCenterX * 0.875).toFloat()
            sMinuteHandLength = (mCenterX * 0.75).toFloat()
            sHourHandLength = (mCenterX * 0.5).toFloat()


            /* Scale loaded background image (more efficient) if surface dimensions change. */
            val scale = width.toFloat() / mBackgroundBitmap.width.toFloat()

            mBackgroundBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                    (mBackgroundBitmap.width * scale).toInt(),
                    (mBackgroundBitmap.height * scale).toInt(), true)

            /*
             * Create a gray version of the image only if it will look nice on the device in
             * ambient mode. That means we don't want devices that support burn-in
             * protection (slight movements in pixels, not great for images going all the way to
             * edges) and low ambient mode (degrades image quality).
             *
             * Also, if your watch face will know about all images ahead of time (users aren't
             * selecting their own photos for the watch face), it will be more
             * efficient to create a black/white version (png, etc.) and load that when you need it.
             */
            if (!mBurnInProtection && !mLowBitAmbient) {
                initGrayBackgroundBitmap()
            }
        }

        private fun initGrayBackgroundBitmap() {
            mGrayBackgroundBitmap = Bitmap.createBitmap(
                    mBackgroundBitmap.width,
                    mBackgroundBitmap.height,
                    Bitmap.Config.ARGB_8888)
            val canvas = Canvas(mGrayBackgroundBitmap)
            val grayPaint = Paint()
            val colorMatrix = ColorMatrix()
            colorMatrix.setSaturation(0f)
            val filter = ColorMatrixColorFilter(colorMatrix)
            grayPaint.colorFilter = filter
            canvas.drawBitmap(mBackgroundBitmap, 0f, 0f, grayPaint)
        }

        /**
         * Captures tap event (and tap type). The [WatchFaceService.TAP_TYPE_TAP] case can be
         * used for implementing specific logic to handle the gesture.
         */
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TOUCH -> {
                    // The user has started touching the screen.
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> {
                    // The user has started a different gesture or otherwise cancelled the tap.
                }
                WatchFaceService.TAP_TYPE_TAP -> {
                    var centerDistX = mCenterX - x
                    var centerDistY = mCenterY - y
                    var dist = Math.sqrt((centerDistX * centerDistX + centerDistY * centerDistY).toDouble())
                    if (dist < width / 5.0f) {
                        var intent = Intent(applicationContext, PassActivity::class.java)
                        startActivity(intent)
                    } else {
                        tapCounter++
                        if (tapCounter > fpPointArr.size) {
                            tapCounter = 0
                        }
                    }
                }
            }
            invalidate()
        }

        fun refresh() {
            tapCounter = 0
            cognitoManager.subscribeToLogin { ex ->
                if (ex == null) {
                    appSync.updateFastPasses(object: AppSyncTest.UpdateFastPassesCallback {
                        override fun onResponse(response: List<DisFastPassTransaction>, nextSelection: String?) {
                            async(UI) {
                                if (nextSelection != null) {
                                    nextSelectionTime = parseFpDate(nextSelection)
                                    if (nextSelectionTime != null && nextSelectionTime!! > mCalendar.time) {
                                        nextSelectionStr = hourFormatter.format(nextSelectionTime)
                                    } else {
                                        nextSelectionStr = ""
                                        nextSelectionTime = null
                                    }
                                }
                                else {
                                    nextSelectionStr = ""
                                    nextSelectionTime = null
                                }

                                var pointsCopy = TreeMap<Long, ArrayList<FPPoint>>(fpPoints)
                                fpPoints.clear()
                                for (tran in response) {
                                    if (tran.fpDT() != null) {
                                        var date = parseFpDate(tran.fpDT()!!)
                                        var fpPoint: FPPoint? = null
                                        var list = pointsCopy[date.time]
                                        if (list != null) {
                                            fpPoint = list.find { it ->
                                                it.rideID == tran.rideID()
                                            }
                                        }
                                        if (fpPoint == null) {
                                            fpPoint = FPPoint(rideManager, cfm, tran.rideID()!!, date)
                                            fpPoint!!.init()
                                        }
                                        var newList = fpPoints[date.time]
                                        if (newList == null) {
                                            newList = ArrayList()
                                            fpPoints[date.time] = newList
                                        }
                                        newList!!.add(fpPoint)
                                    }
                                }
                                fpPointArr.clear()
                                for (entry in fpPoints) {
                                    for (point in entry.value) {
                                        fpPointArr.add(point)
                                    }
                                }
                            }
                        }

                        override fun onError(ec: Int?, msg: String?) {

                        }
                    })
                }
            }
        }

        private fun genTillStr(fpDate: Date): String {
            var diff = fpDate.time - Date().time
            var till = true
            if (diff < 0) {
                //add an hour to switch to fast pass ending time
                diff += 60 * 60 * 1000
                till = false
            }
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            var hoursFlt = hours.toFloat() + (minutes % 60).toFloat()/60f

            var tillStr = ""
            if (till) {
                tillStr += "Ready in\n"
            } else if (diff > 0) {
                tillStr += "Exp in\n"
            }
            if (hours > 2) {
                tillStr += "~" + Math.abs(hoursFlt).roundToInt().toString() + " hrs"
            } else if (hours == 0L) {
                tillStr += Math.abs(minutes % 60).toString() + " mins"
            } else {
                tillStr += Math.abs(hours).toString() + ":" + Math.abs(minutes % 60).toString()
            }
            if (!till && diff <= 0) {
                tillStr += "\nlate"
            }
            return tillStr
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = System.currentTimeMillis()
            mCalendar.timeInMillis = now

            drawBackground(canvas)

            var textPaint = TextPaint()
            textPaint.isAntiAlias = true
            textPaint.color = Color.WHITE
            textPaint.textSize = 30f
            textPaint.textAlign = Paint.Align.CENTER

            var xPos = (canvas.getWidth() / 2).toFloat()

            if (tapCounter > 0 && fpPointArr.size > 0) {
                var colorI = (tapCounter - 1) % colors.size
                var circlePaint = Paint()
                circlePaint.isAntiAlias = true
                circlePaint.color = colors[colorI]
                canvas.drawCircle(mBackgroundBitmap.width.toFloat()/2f, mBackgroundBitmap.height.toFloat()/2f, mBackgroundBitmap.width.toFloat()/5f, circlePaint)

                var fpPointI = (tapCounter - 1) % fpPointArr.size
                var fpPoint = fpPointArr[fpPointI]
                if (fpPoint.rideImgBmp != null) {
                    canvas.drawBitmap(fpPoint.rideImgBmp, (mCenterX - fpPoint.rideImgBmp!!.width/2), (mCenterY - fpPoint.rideImgBmp!!.height/2), mBackgroundPaint)
                }

                var yPos = ((5 * canvas.getHeight() / 6) - ((textPaint.descent() + textPaint.ascent()) / 2))
                canvas.drawText(hourFormatter.format(fpPoint.fpTime), xPos, yPos, textPaint)

                yPos = ((canvas.getHeight() / 8) - ((textPaint.descent() + textPaint.ascent()) / 2))
                var tillStr = genTillStr(fpPoint.fpTime)
                textPaint.textSize = 16f
                for (line in tillStr.split("\n")) {
                  canvas.drawText(line, xPos, yPos, textPaint)
                  yPos += textPaint.descent() - textPaint.ascent()
                }
            } else {
                var circleColor = Color.rgb(30, 0, 0)
                if (nextSelectionTime != null) {
                    var yPos = ((canvas.getHeight() / 8) - ((textPaint.descent() + textPaint.ascent()) / 2))

                    var tillStr = genTillStr(nextSelectionTime!!)
                    textPaint.textSize = 16f
                    for (line in tillStr.split("\n")) {
                        canvas.drawText(line, xPos, yPos, textPaint);
                        yPos += textPaint.descent() - textPaint.ascent();
                    }
                    textPaint.textSize = 30f
                    yPos = ((5 * canvas.getHeight() / 6) - ((textPaint.descent() + textPaint.ascent()) / 2))
                    canvas.drawText(nextSelectionStr, xPos, yPos, textPaint)
                } else {
                    circleColor = Color.rgb(0, 30, 0)
                }
                var circlePaint = Paint()
                circlePaint.isAntiAlias = true
                circlePaint.color = circleColor
                canvas.drawCircle(mBackgroundBitmap.width.toFloat()/2f, mBackgroundBitmap.height.toFloat()/2f, mBackgroundBitmap.width.toFloat()/5f, circlePaint)
            }

            var colorI = 0

            var lastEndDegs = ArrayList<Float>()
            for (fpPoint in fpPointArr) {
                if (colorI >= colors.size) {
                    colorI = 0
                }
                var paint = Paint()
                paint.strokeWidth = 5f
                paint.color = colors[colorI]
                paint.isAntiAlias = true
                paint.strokeCap = Paint.Cap.ROUND
                paint.style = Paint.Style.STROKE

                calendar.time = fpPoint.fpTime
                var hours = calendar.get(Calendar.HOUR_OF_DAY)
                var minutes = calendar.get(Calendar.MINUTE)
                Log.d("FPTIME", hours.toString() + ":" + minutes.toString())
                var totalMins = hours * 60 + minutes
                if (totalMins > 12 * 60) {
                    totalMins -= 12 * 60
                }
                var startDeg = (totalMins.toFloat() / (12 * 60).toFloat()) * 360.0f - 90.0f

                Log.d("FPDEG", startDeg.toString())
                var height = 13f
                for (endDeg in lastEndDegs) {
                    if (endDeg > startDeg) {
                        height += 8f
                    }
                }
                lastEndDegs.add(startDeg + 30f)
                canvas.drawArc(height, height, mBackgroundBitmap.width.toFloat() - height, mBackgroundBitmap.height.toFloat() - height, startDeg, 30f, false, paint)
                colorI++
            }
            drawWatchFace(canvas)
        }

        private fun drawBackground(canvas: Canvas) {
            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK)
            } else if (mAmbient) {
                canvas.drawBitmap(mGrayBackgroundBitmap, 0f, 0f, mBackgroundPaint)
            } else {
                canvas.drawBitmap(mBackgroundBitmap, 0f, 0f, mBackgroundPaint)
            }
        }

        private fun drawWatchFace(canvas: Canvas) {

            /*
             * Draw ticks. Usually you will want to bake this directly into the photo, but in
             * cases where you want to allow users to select their own photos, this dynamically
             * creates them on top of the photo.
             */
            val innerTickRadius = mCenterX - 10
            val outerTickRadius = mCenterX
            for (tickIndex in 0..11) {
                val tickRot = (tickIndex.toDouble() * Math.PI * 2.0 / 12).toFloat()
                val innerX = Math.sin(tickRot.toDouble()).toFloat() * innerTickRadius
                val innerY = (-Math.cos(tickRot.toDouble())).toFloat() * innerTickRadius
                val outerX = Math.sin(tickRot.toDouble()).toFloat() * outerTickRadius
                val outerY = (-Math.cos(tickRot.toDouble())).toFloat() * outerTickRadius
                canvas.drawLine(mCenterX + innerX, mCenterY + innerY,
                        mCenterX + outerX, mCenterY + outerY, mTickAndCirclePaint)
            }

            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            val seconds =
                    mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f
            val secondsRotation = seconds * 6f

            val minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f

            val hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f
            val hoursRotation = mCalendar.get(Calendar.HOUR) * 30 + hourHandOffset

            /*
             * Save the canvas state before we can begin to rotate it.
             */
            canvas.save()

            canvas.rotate(hoursRotation, mCenterX, mCenterY)
            canvas.drawLine(
                    mCenterX,
                    mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                    mCenterX,
                    mCenterY - sHourHandLength,
                    mHourPaint)

            canvas.rotate(minutesRotation - hoursRotation, mCenterX, mCenterY)
            canvas.drawLine(
                    mCenterX,
                    mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                    mCenterX,
                    mCenterY - sMinuteHandLength,
                    mMinutePaint)

            /*
             * Ensure the "seconds" hand is drawn only when we are in interactive mode.
             * Otherwise, we only update the watch face once a minute.
             */
            if (!mAmbient) {
                mSecondPaint.color = Color.WHITE
                if (tapCounter > 0) {
                    var colorI = (tapCounter - 1) % colors.size
                    mSecondPaint.color = colors[colorI]
                } else if (fpPointArr.size > 0) {
                    var now = Date().time
                    var i = 0
                    for (fpPoint in fpPointArr) {
                        if (now >= fpPoint.fpTime.time && now < fpPoint.fpTime.time + 3600000L) {
                            mSecondPaint.color = colors[i % colors.size]
                            break
                        }
                        //Array is ordered, so if now is less than this fast pass time, there is nothing left for it
                        if (now < fpPoint.fpTime.time) {
                            break
                        }
                        i++
                    }
                }
                canvas.rotate(secondsRotation - minutesRotation, mCenterX, mCenterY)
                canvas.drawLine(
                        mCenterX,
                        mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                        mCenterX,
                        mCenterY - mSecondHandLength,
                        mSecondPaint)

            }
            canvas.drawCircle(
                    mCenterX,
                    mCenterY,
                    CENTER_GAP_AND_CIRCLE_RADIUS,
                    mTickAndCirclePaint)

            /* Restore the canvas' original orientation. */
            canvas.restore()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.timeZone = TimeZone.getDefault()
                refresh()
                invalidate()
            } else {
                unregisterReceiver()
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer()
        }

        private fun registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@FastPassFace.registerReceiver(mTimeZoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            this@FastPassFace.unregisterReceiver(mTimeZoneReceiver)
        }

        /**
         * Starts/stops the [.mUpdateTimeHandler] timer based on the state of the watch face.
         */
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer
         * should only run in active mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !mAmbient
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }

        private fun parseFpDate(str: String): Date {
            try {
                return fpDateFormat.parse(str)
            } catch (ex: ParseException) {
                return fpDateFormat2.parse(str)
            }
        }
    }
}


