package com.appsync.ajcra.watchtest2

import android.animation.ArgbEvaluator
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.PagerSnapHelper
import android.support.v7.widget.RecyclerView
import android.support.wearable.activity.WearableActivity
import android.util.Log
import android.view.View
import android.widget.AbsListView
import android.widget.ProgressBar
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.dis.ajcra.distest2.PassManager
import com.dis.ajcra.fastpass.fragment.DisPass
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import java.io.File
import android.util.DisplayMetrics



class PassActivity : WearableActivity() {
    companion object {
        private var colorArr = arrayListOf(Color.rgb(255, 200, 200), Color.rgb(200, 255, 200), Color.rgb(200, 200, 255), Color.rgb(255, 200, 255))
    }

    private lateinit var cognitoManager: CognitoManager
    private lateinit var profileManager: ProfileManager
    private lateinit var passManager: PassManager
    private lateinit var cfm: CloudFileManager

    private var position = 0
    private var scrollLevel = 0

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var profileImg: CircleImageView

    private lateinit var adapter: PassRecyclerAdapter
    private var passProfiles = ArrayList<Profile>()
    private var dataset = ArrayList<DisPass>()

    private var listPassCB = object: PassManager.ListPassesCB {
        override fun passUpdated(userID: String, passes: List<DisPass>) {
            async(UI) {
                var profile: Profile?
                var profileI = passProfiles.indexOfFirst { it ->
                    it.id == userID
                }
                if (profileI < 0) {
                    profileI = passProfiles.size
                    profile = profileManager.getProfile(userID)
                } else {
                    profile = passProfiles.get(profileI)
                }
                if (profile != null) {
                    /*
                       This code should prevent duplicate passes
                    */
                    for (pass in passes) {
                        var existingPassI = dataset.indexOfFirst { it ->
                            it.id() == pass.id()
                        }
                        if (existingPassI < 0) {
                            passProfiles.add(profileI, profile)
                            dataset.add(profileI, pass)
                            adapter.notifyItemInserted(profileI)
                            profileI++
                        } else {
                            //Will the pass information ever change?
                            dataset.set(existingPassI, pass)
                            adapter.notifyItemChanged(existingPassI)
                        }
                    }
                }
            }
        }

        override fun updateCompleted() {
            async(UI) {
                recyclerView.visibility = View.VISIBLE
                progressBar.visibility = View.GONE

                if (passProfiles.size > 0) {
                    setProfile()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        passManager.listPasses()
    }

    override fun onStop() {
        super.onStop()
        passManager.unsubscribeFromPasses(listPassCB)
    }

    fun setProfile() {
        if (position >= 0 && position < passProfiles.size) {
            recyclerView.setBackgroundColor(colorArr.get(position % colorArr.size))
            var profile = passProfiles.get(position)
            async(UI) {
                if (profile != null) {
                    Log.d("STATE", "PROFILE IS NULL")
                }
                var profPicUrl = profile.getProfilePicUrl().await()
                if (profPicUrl == null) {
                    profPicUrl = "profileImgs/blank-profile-picture-973460_640.png"
                }
                cfm.download(profPicUrl, object : CloudFileListener() {
                    override fun onStateChanged(id: Int, state: TransferState?) {}

                    override fun onError(id: Int, ex: Exception?) {}

                    override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {}

                    override fun onComplete(id: Int, file: File) {
                        async {
                            var options = BitmapFactory.Options()
                            options.inJustDecodeBounds = true
                            BitmapFactory.decodeFile(file.absolutePath, options)
                            var imgScale = 1
                            while (options.outWidth / imgScale > 400) {
                                imgScale *= 2
                            }
                            options.inJustDecodeBounds = false
                            options.inSampleSize = imgScale
                            var bmap = BitmapFactory.decodeFile(file.absolutePath, options)
                            async(UI) {
                                profileImg.setImageBitmap(bmap)
                            }
                        }
                    }
                })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pass)
        cognitoManager = CognitoManager.GetInstance(applicationContext)
        profileManager = ProfileManager(cognitoManager)
        passManager = PassManager.GetInstance(applicationContext)
        cfm = CloudFileManager.GetInstance(cognitoManager, applicationContext)
        passManager.subscribeToPasses(listPassCB)

        recyclerView = findViewById(R.id.pass_recyclerview)
        progressBar = findViewById(R.id.pass_progressbar)
        profileImg = findViewById(R.id.pass_profimg)

        recyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        adapter = PassRecyclerAdapter(dataset, displayMetrics.widthPixels, displayMetrics.heightPixels)
        val helper = PagerSnapHelper()
        helper.attachToRecyclerView(recyclerView)
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object: RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView?, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                Log.d("STATE", "StateChange: " + newState)
                if (newState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    position = (recyclerView?.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
                    if (position < 0) {
                        position = 0
                    }
                    scrollLevel = 0
                    setProfile()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                var c1 = colorArr.get(position % colorArr.size)
                var c2 = 0
                if (scrollLevel > 0) {
                    c2 = colorArr.get((position + 1) % colorArr.size)
                }  else {
                    if (position - 1 < 0) {
                        c2 = colorArr.get(position % colorArr.size)
                    } else {
                        c2 = colorArr.get((position - 1) % colorArr.size)
                    }
                }
                scrollLevel += dx
                Log.d("STATE", "SCROLL LEVEL: " + scrollLevel)
                recyclerView?.setBackgroundColor(ArgbEvaluator().evaluate(Math.abs(scrollLevel.toFloat() / recyclerView?.width), c1, c2) as Int)
            }
        })
    }
}
