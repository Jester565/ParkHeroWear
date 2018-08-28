package com.appsync.ajcra.watchtest2

import android.util.Log
import com.appsync.ajcra.watchtest2.model.AddFriendInput
import com.appsync.ajcra.watchtest2.model.RemoveFriendInput
import com.appsync.ajcra.watchtest2.model.UserInfo
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import org.json.JSONObject

/**
 * Created by ajcra on 1/18/2018.
 */
open class Profile {
    var id: String
    var name: String? = null
    var aquiredProfilePic: Boolean = false
    var profilePicUrl: String? = null
    var inviteStatus: Int = -1
    var apiClient: DisneyAppClient

    constructor(apiClient: DisneyAppClient, id: String) {
        this.apiClient = apiClient
        this.id = id
    }

    constructor(apiClient: DisneyAppClient, userInfo: UserInfo) {
        this.apiClient = apiClient;
        this.name = userInfo.name
        this.id = userInfo.id
        aquiredProfilePic = true
        this.profilePicUrl = userInfo.profilePicUrl
    }

    constructor(apiClient: DisneyAppClient, userObj: JSONObject) {
        this.apiClient = apiClient;
        this.name = userObj.getString("name")
        this.id = userObj.getString("id")
        aquiredProfilePic = true
        this.profilePicUrl = userObj.getString("profilePicUrl")
    }

    fun getUser() = async {
        var output = apiClient.getuserGet(id)
        name = output.name
        aquiredProfilePic = true
        profilePicUrl = output.profilePicUrl
    }

    fun getName(): Deferred<String> = async {
        if (name == null) {
            getUser().await()
        }
        name as String
    }

    fun getProfilePicUrl(): Deferred<String?> = async {
        if (!aquiredProfilePic) {
            getUser().await()
        }
        profilePicUrl
    }

    fun getFriends(): Deferred<List<Profile>> = async {
        var friendArr = ArrayList<Profile>()
        try {
            var output = apiClient.getfriendsGet(id)
            for (friendInfo in output.friends) {
                friendArr.add(Profile(apiClient, friendInfo))
            }
        } catch (ex: Exception) {
            Log.d("STATE", "Could not get friends: " + ex.message)
        }
        friendArr
    }

    fun getInviteStatus(): Deferred<Int> = async {
        if (inviteStatus < 0) {
            try {
                var output = apiClient.getinvitestatusGet(id)
                inviteStatus = output.inviteStatus
            } catch (ex: Exception) {
                Log.d("STATE", "Could not get inviate status: " + ex.message)
            }
        }
        inviteStatus
    }

    fun addFriend(): Deferred<Boolean> = async {
        var nowFriend = false
        try {
            var input = AddFriendInput()
            input.friendId = id
            var output = apiClient.addfriendPost(input)
            nowFriend = output.nowFriend
        } catch(ex: Exception) {
            Log.d("STATE","Add friend error: " + ex.message)
        }
        nowFriend
    }

    fun removeFriend() = async {
        var nowFriend = false
        try {
            var input = RemoveFriendInput()
            input.friendId = id
            apiClient.removefriendPost(input)
        } catch(ex: Exception) {
            Log.d("STATE", "Could not remove friend: " + ex.message)
        }
    }
}