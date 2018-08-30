package com.appsync.ajcra.watchtest2

import android.content.Context
import android.os.Handler
import android.util.Log
import com.amazonaws.AmazonServiceException
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.cognitoidentityprovider.*
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.*
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.*
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.cognitoidentity.model.NotAuthorizedException
import com.amazonaws.services.cognitoidentityprovider.AmazonCognitoIdentityProviderClient
import com.amazonaws.services.cognitoidentityprovider.model.MFAMethodNotFoundException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import java.util.*
import kotlin.collections.HashMap


class CognitoManager {
    val userID: String?
        get() = if (user != null) {
            user!!.userId
        } else null

    val federatedID: String
        get() = credentialsProvider.identityId

    private var user: CognitoUser? = null
    private val userPool: CognitoUserPool
    val credentialsProvider: CognitoCachingCredentialsProvider

    private var refreshHandler: Handler? = null
    private var refreshCB: Runnable? = null

    private var loginHandlers = HashMap<String, (Exception?) -> Unit>()

    private var gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken("484305592931-pcg7s9kmq920p2csgmmc4ga0suo98vuh.apps.googleusercontent.com")
            .build()

    private var googleSignInClient: GoogleSignInClient

    fun hasCredentials(): Deferred<Boolean> = async {
        var hasCreds = false
        try {
            credentialsProvider.refresh()
        } catch (ex: Exception) {
            Log.d("REFRESHEX", ex.message)
        }
        try {
            hasCreds = (credentialsProvider.logins != null && credentialsProvider.logins.size > 0)
        } catch (ex: Exception) {
            Log.d("REFRESHEX", ex.message)
        }
        hasCreds
    }

    constructor(appContext: Context) {
        credentialsProvider = CognitoCachingCredentialsProvider(
                appContext, COGNITO_IDENTITY_POOL_ID, COGNITO_REGION
        )

        val identityProviderClient = AmazonCognitoIdentityProviderClient(credentialsProvider, ClientConfiguration())
        identityProviderClient.setRegion(Region.getRegion(Regions.US_WEST_2))

        userPool = CognitoUserPool(appContext, COGNITO_USER_POOL_ID, COGNITO_CLIENT_ID, COGNITO_CLIENT_SECRET, identityProviderClient)
        user = userPool.currentUser

        googleSignInClient = GoogleSignIn.getClient(appContext, gso)

        refreshLogin()
    }

    fun refreshLogin() {
        async {
            refreshHandler?.removeCallbacks(refreshCB)
            refreshHandler = null
            refreshCB = null

            var result = googleSignInClient.silentSignIn()
            if (result.isComplete) {
                //The current token is still valid
                if (result.isSuccessful) {
                    addLogin("accounts.google.com", result.getResult().idToken!!).await()
                    for (entry in loginHandlers) {
                        entry.value.invoke(null)
                    }
                } else {
                    refreshSession()
                }
            } else {
                //We need to get a new token
                result.addOnCompleteListener { res ->
                    if (result.isSuccessful) {
                        async {
                            addLogin("accounts.google.com", result.getResult().idToken!!).await()
                            for (entry in loginHandlers) {
                                entry.value.invoke(null)
                            }
                        }
                    } else {
                        refreshSession()
                    }
                }
            }
        }
    }
    fun refreshSession() {
        val handler = object : AuthenticationHandler {
            override fun onSuccess(userSession: CognitoUserSession?, device: CognitoDevice?) {
                async(UI) {
                    addLogin(COGNITO_USER_POOL_ARN, userSession!!.idToken.jwtToken).await()
                    for (entry in loginHandlers) {
                        entry.value.invoke(null)
                    }

                    refreshCB = Runnable {
                        refreshLogin()
                    }
                    refreshHandler = Handler()
                    refreshHandler?.postDelayed(refreshCB, credentialsProvider.sessionCredentitalsExpiration.time - Date().time - 1000 * 30)
                }
            }

            override fun getAuthenticationDetails(authenticationContinuation: AuthenticationContinuation?, userId: String?) {
                Log.e("COGNITO", "Session Expired")
                for (entry in loginHandlers) {
                    entry.value.invoke(NotAuthorizedException("Session Expired"))
                }
            }

            override fun authenticationChallenge(continuation: ChallengeContinuation?) {
                Log.e("COGNITO", "Not authenticated")
                for (entry in loginHandlers) {
                    entry.value.invoke(NotAuthorizedException("Not authenticated"))
                }
            }

            override fun getMFACode(continuation: MultiFactorAuthenticationContinuation?) {
                Log.e("COGNITO", "MFA required")
                for (entry in loginHandlers) {
                    entry.value.invoke(MFAMethodNotFoundException("MFA Required"))
                }
            }

            override fun onFailure(exception: Exception?) {
                Log.e("COGNITO", "Failure: " + exception?.message)
                for (entry in loginHandlers) {
                    entry.value.invoke(exception!!)
                }
            }
        }
        user!!.getSessionInBackground(handler)
    }

    fun subscribeToLogin(cb: (Exception?) -> Unit): String {
        var token = UUID.randomUUID().toString()
        loginHandlers[token] = cb
        async(UI) {
            try {
                if (credentialsProvider.sessionCredentitalsExpiration > Date()) {
                    cb(null)
                }
            } catch (ex: Exception) {  //sessionCredentialsExpiration is null, so the user is not logged in (happens on firsttime boot)
                cb(null)
            }
        }
        return token
    }

    fun unsubscribeFromLogin(token: String) {
        loginHandlers.remove(token)
    }

    interface RegisterUserHandler {
        fun onSuccess()

        fun onVerifyRequired(deliveryMethod: String, deliveryDest: String)

        fun onFailure(ex: Exception)
    }

    fun registerUser(userName: String, email: String, pwd: String, cb: CognitoManager.RegisterUserHandler) {
        val userAttributes = CognitoUserAttributes()
        userAttributes.addAttribute("email", email)
        val signUpHandler = object : SignUpHandler {
            override fun onSuccess(registeredUser: CognitoUser, signUpConfirmationState: Boolean, cognitoUserCodeDeliveryDetails: CognitoUserCodeDeliveryDetails) {
                user = registeredUser
                if (!signUpConfirmationState) {
                    cb.onVerifyRequired(
                            cognitoUserCodeDeliveryDetails.deliveryMedium,
                            cognitoUserCodeDeliveryDetails.destination)
                } else {
                    cb.onSuccess()
                }
            }

            override fun onFailure(exception: Exception) {
                cb.onFailure(exception)
            }
        }
        userPool.signUpInBackground(userName, pwd, userAttributes, null, signUpHandler)
    }

    fun validateUser(code: String, cb: GenericHandler) {
        if (user != null) {
            user!!.confirmSignUpInBackground(code, false, cb)
        } else {
            cb.onFailure(Exception("ValidateUser: user not set"))
        }
    }


    interface UserAttributesHandler {
        fun onSuccess(attribMap: Map<String, String>)
        fun onFailure(ex: Exception)
    }

    fun getUserAttributes(cb: UserAttributesHandler) {
        val getDetailsHandler = object : GetDetailsHandler {
            override fun onSuccess(cognitoUserDetails: CognitoUserDetails) {
                val attribMap = cognitoUserDetails.attributes.attributes
                val iterator = attribMap.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    Log.d("STATUS", "Entry " + entry.key + " : " + entry.value)
                }
                cb.onSuccess(attribMap)
            }

            override fun onFailure(exception: Exception) {
                cb.onFailure(exception)
            }
        }
        if (user != null) {
            user!!.getDetailsInBackground(getDetailsHandler)
        } else {
            cb.onFailure(Exception("GetUserAttributes: User not set"))
        }
    }

    abstract class LoginHandler {
        abstract fun onSuccess()
        abstract fun onMFA(continuation: MultiFactorAuthenticationContinuation)
        open fun onUnverified(ex: Exception) {
            onFailure(ex)
        }

        fun onNoUser(ex: Exception) {
            onFailure(ex)
        }

        fun onBadPwd(ex: Exception) {
            onFailure(ex)
        }

        abstract fun onFailure(ex: Exception)
    }

    fun login(username: String, pwd: String, cb: LoginHandler) {
        if (user == null || user!!.userId !== username) {
            user = userPool.getUser(username)
        }
        login(pwd, cb)
    }

    fun login(pwd: String, cb: LoginHandler) {
        val handler = object : AuthenticationHandler {
            override fun onSuccess(userSession: CognitoUserSession?, device: CognitoDevice?) {
                async(UI) {
                    addLogin(COGNITO_USER_POOL_ARN, userSession!!.idToken.jwtToken)
                    refreshLogin()
                    cb.onSuccess()
                }
            }

            override fun getAuthenticationDetails(authenticationContinuation: AuthenticationContinuation?, userId: String?) {
                val details = AuthenticationDetails(userId, pwd, null)

                authenticationContinuation!!.setAuthenticationDetails(details)
                authenticationContinuation!!.continueTask()
            }

            override fun authenticationChallenge(continuation: ChallengeContinuation?) {
                Log.d("STATE", "Auth challenge")
            }

            override fun getMFACode(continuation: MultiFactorAuthenticationContinuation?) {
                cb.onMFA(continuation!!)
            }

            override fun onFailure(exception: Exception?) {
                if (exception is AmazonServiceException) {
                    val errCode = exception.errorCode
                    when (errCode) {
                        "UserNotConfirmedException" -> {
                            cb.onUnverified(exception)
                            return
                        }
                        "UserNotFoundException" -> {
                            cb.onNoUser(exception)
                            return
                        }
                    }
                }
                Log.d("STATE", "Login exception " + exception)
                cb.onFailure(exception!!)
            }
        }
        if (user != null) {
            user!!.getSessionInBackground(handler)
        } else {
            cb.onFailure(Exception("Login: user not set"))
        }
    }

    fun addLogin(provider: String, token: String) = async {
        val logins = HashMap<String, String>()
        logins.put(provider, token)
        for ((key) in credentialsProvider.logins) {
            Log.d("STATE", "Login: " + key)
        }
        credentialsProvider.clear()
        for ((key) in credentialsProvider.logins) {
            Log.d("STATE", "Login: " + key)
        }
        credentialsProvider.logins = logins
        credentialsProvider.refresh()
        Log.d("STATE", "IdentityID: " + credentialsProvider.identityId)
        Log.d("STATE", "Aws AccessID: " + credentialsProvider.credentials.awsAccessKeyId)
        Log.d("STATE", "Aws Secret: " + credentialsProvider.credentials.awsSecretKey)
        Log.d("STATE", "Token: " + credentialsProvider.credentials.sessionToken)
    }

    interface ResetPwdHandler {
        fun onSuccess()
        fun onContinuation(continuation: ForgotPasswordContinuation)
        fun onFailure(ex: Exception)
    }

    fun resetPwd(username: String, cb: ResetPwdHandler) {
        if (user == null || user!!.userId !== username) {
            user = userPool.getUser(username)
        }
        resetPwd(cb)
    }

    fun resetPwd(cb: ResetPwdHandler) {
        user!!.forgotPasswordInBackground(object : ForgotPasswordHandler {
            override fun onSuccess() {
                cb.onSuccess()
            }

            override fun getResetCode(continuation: ForgotPasswordContinuation) {
                cb.onContinuation(continuation)
            }

            override fun onFailure(exception: Exception) {
                cb.onFailure(exception)
            }
        })
    }

    interface ResendCodeHandler {
        fun onSuccess(delvMeth: String, delvDest: String)
        fun onFailure(ex: Exception)
    }

    fun resendCode(handler: ResendCodeHandler) {
        user!!.resendConfirmationCodeInBackground(object : VerificationHandler {
            override fun onSuccess(verificationCodeDeliveryMedium: CognitoUserCodeDeliveryDetails) {
                handler.onSuccess(verificationCodeDeliveryMedium.deliveryMedium, verificationCodeDeliveryMedium.destination)
            }

            override fun onFailure(exception: Exception) {
                handler.onFailure(exception)
            }
        })
    }

    companion object {
        private val COGNITO_USER_POOL_ID = "us-west-2_PkZb6onNf"
        private val COGNITO_IDENTITY_POOL_ID = "us-west-2:76a1b798-741a-4a5e-9b7e-4112e4fd0acb"
        private val COGNITO_CLIENT_ID = "4sk070sudo8u3qu4qjrnvv513a"
        private val COGNITO_CLIENT_SECRET = "1bfhumogg5j2u297nie4fv1u5mn58bn92iq8r8edfv1vtarloapc"
        private val COGNITO_REGION = Regions.US_WEST_2
        private val COGNITO_USER_POOL_ARN = "cognito-idp.us-west-2.amazonaws.com/us-west-2_PkZb6onNf"

        private var instance: CognitoManager? = null

        fun GetInstance(appContext: Context): CognitoManager {
            if (instance == null) {
                instance = CognitoManager(appContext)
            }
            return instance as CognitoManager
        }
    }
}
