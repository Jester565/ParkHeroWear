package com.appsync.ajcra.watchtest2

import android.util.Log
import com.amazonaws.mobileconnectors.apigateway.ApiClientFactory
import com.appsync.ajcra.watchtest2.model.CreateUserInput
import com.appsync.ajcra.watchtest2.model.SendEntityInput
import com.appsync.ajcra.watchtest2.model.UserInfo
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import org.json.JSONObject

class ProfileManager {
    var apiClient: DisneyAppClient
    var cognitoManager: CognitoManager

    companion object {
        var PROF_SETTINGS_NAME = "prof_prefs"
    }

    constructor(cognitoManager: CognitoManager) {
        this.cognitoManager = cognitoManager
        val factory = ApiClientFactory()
                .credentialsProvider(cognitoManager.credentialsProvider)
        apiClient = factory.build(DisneyAppClient::class.java)
    }

    fun getProfile(id: String, name: String? = null, profilePicUrl: String? = null): Profile {
        var profile = Profile(apiClient, id)
        profile.name = name
        profile.profilePicUrl = profilePicUrl
        return profile
    }

    fun getProfile(info: UserInfo): Profile {
        return Profile(apiClient, info)
    }

    fun getProfile(uObj: JSONObject): Profile {
        return Profile(apiClient, uObj)
    }

    fun sendEntity(objKey: String, sendToProfiles: ArrayList<Profile>): Deferred<Boolean> = async {
        var success = false
        try {
            var input = SendEntityInput()
            input.objKey = objKey
            var sendIdArr = ArrayList<String>()
            sendToProfiles.forEach {it ->
                sendIdArr.add(it.id)
            }
            input.sendToIds = sendIdArr
            apiClient.sendentityPost(input)
            success = true
        } catch (ex: Exception) {
            Log.d("STATE", "SendEntity exception: " + ex.message)
        }
        success
    }

    fun getUsers(prefix: String): Deferred<ArrayList<Profile>> = async {
        var profiles = ArrayList<Profile>()
        try {
            val output = apiClient.getusersGet("true", prefix)
            var i = 0
            while (i < output.users.size) {
                var profile = Profile(apiClient, output.users[i])
                profile.inviteStatus = output.inviteStatuses[i]
                profiles.add(profile)
                i++
            }
        } catch (ex: Exception) {
            Log.d("STATE", "Get users exception: " + ex.message)
        }

        profiles
    }
}