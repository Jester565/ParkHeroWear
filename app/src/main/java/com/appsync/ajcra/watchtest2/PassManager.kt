package com.dis.ajcra.distest2

import android.content.Context
import android.util.Log
import com.appsync.ajcra.watchtest2.AppSyncTest
import com.appsync.ajcra.watchtest2.CognitoManager
import com.appsync.ajcra.watchtest2.ProfileManager
import com.dis.ajcra.fastpass.ListPassesQuery
import com.dis.ajcra.fastpass.fragment.DisPass
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlin.coroutines.experimental.suspendCoroutine

class PassManager {
    companion object {
        fun GetInstance(ctx: Context): PassManager {
            if (passManager == null) {
                passManager = PassManager(ctx)
            }
            return passManager!!
        }

        private var passManager: PassManager? = null
    }

    private var profileManager: ProfileManager
    private var appSync: AppSyncTest
    private var subscribers = HashSet<ListPassesCB>()

    constructor(ctx: Context) {
        appSync = AppSyncTest.getInstance(ctx)
        var cognitoManager = CognitoManager.GetInstance(ctx)
        profileManager = ProfileManager(cognitoManager)
    }

    interface ListPassesCB {
        fun passUpdated(userID: String, passes: List<DisPass>)
        fun updateCompleted()
    }

    fun subscribeToPasses(cb: ListPassesCB) {
        subscribers.add(cb)
    }

    fun listPasses() {
        appSync.listPasses(object: AppSyncTest.ListPassesCallback {
            override fun onResponse(response: List<ListPassesQuery.ListPass>) {
                for (userPasses in response) {
                    var disPasses = ArrayList<DisPass>()
                    var passes = userPasses.passes()
                    for (subscriber in subscribers) {
                        subscriber.passUpdated("us-west-2:0181a50d-7489-4173-9ffd-659269ed05d1", disPasses)
                    }
                    if (passes != null) {
                        for (pass in passes) {
                            Log.d("PASS", "PASS AQUIRED")
                            disPasses.add(pass.fragments().disPass())
                        }
                        for (subscriber in subscribers) {
                            subscriber.passUpdated(userPasses.user()!!, disPasses)
                        }
                    }
                }
                for (subscriber in subscribers) {
                    subscriber.updateCompleted()
                }
            }

            override fun onError(ec: Int?, msg: String?) {

            }
        })
    }
    /*
    suspend fun addPass(passID: String) = suspendCoroutine<DisPass> { cont ->
        appSync.addPass(passID, object: AppSyncTest.AddPassCallback {
            override fun onResponse(response: DisPass) {
                async(UI) {
                    var myProfile = profileManager.genMyProfile().await()
                    var dpList = arrayListOf(response)
                    for (subscriber in subscribers) {
                        subscriber.passUpdated(myProfile.id, dpList)
                    }
                    cont.resume(response)
                }
            }

            override fun onError(ec: Int?, msg: String?) {
                cont.resumeWithException(Exception(msg))
            }
        })
    }
    */

    fun unsubscribeFromPasses(cb: ListPassesCB) {
        subscribers.remove(cb)
    }
}