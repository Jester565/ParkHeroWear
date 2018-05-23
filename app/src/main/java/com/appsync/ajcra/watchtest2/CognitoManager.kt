package com.appsync.ajcra.watchtest2

import android.content.Context
import android.util.Log
import com.amazonaws.AmazonServiceException
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSSessionCredentials
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.cognitoidentityprovider.*
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.*
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.*
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.cognitoidentityprovider.AmazonCognitoIdentityProviderClient
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import java.util.*

class CognitoManager private constructor(appContext: Context) {
    fun isLoggedIn(): Deferred<Boolean> = async{
        credentialsProvider.refresh()
        (credentialsProvider.logins != null && credentialsProvider.logins.size > 0)
    }

    val userID: String?
        get() = if (user != null) {
            user!!.userId
        } else null

    val federatedID: String
        get() = credentialsProvider.identityId

    internal val credentials: AWSSessionCredentials
        get() = credentialsProvider.credentials

    private var user: CognitoUser? = null
    private val userPool: CognitoUserPool
    val credentialsProvider: CognitoCachingCredentialsProvider

    init {
        credentialsProvider = CognitoCachingCredentialsProvider(
                appContext, COGNITO_IDENTITY_POOL_ID, COGNITO_REGION
        )
        //val clientConf = ClientConfiguration()
        val identityProviderClient = AmazonCognitoIdentityProviderClient(credentialsProvider, ClientConfiguration())
        identityProviderClient.setRegion(Region.getRegion(Regions.US_WEST_2))

        userPool = CognitoUserPool(appContext, COGNITO_USER_POOL_ID, COGNITO_CLIENT_ID, COGNITO_CLIENT_SECRET, identityProviderClient)
        user = userPool.currentUser
    }

    interface RegisterUserHandler {
        fun onSuccess()

        fun onVerifyRequired(deliveryMethod: String, deliveryDest: String)

        fun onFailure(ex: Exception)
    }

    fun registerUser(userName: String, email: String, pwd: String, cb: RegisterUserHandler) {
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
        if (user!!.userId !== username) {
            user = userPool.getUser(username)
        }
        login(pwd, cb)
    }

    fun login(pwd: String, cb: LoginHandler) {
        val handler = object : AuthenticationHandler {
            override fun onSuccess(userSession: CognitoUserSession, device: CognitoDevice) {
                addLogin(COGNITO_USER_POOL_ARN, userSession.idToken.jwtToken)
                cb.onSuccess()
            }

            override fun getAuthenticationDetails(authenticationContinuation: AuthenticationContinuation, userId: String) {
                val details = AuthenticationDetails(userId, pwd, null)
                authenticationContinuation.setAuthenticationDetails(details)
                authenticationContinuation.continueTask()
            }

            override fun authenticationChallenge(continuation: ChallengeContinuation?) {

            }

            override fun getMFACode(continuation: MultiFactorAuthenticationContinuation) {
                cb.onMFA(continuation)
            }

            override fun onFailure(exception: Exception) {
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
                cb.onFailure(exception)
            }
        }
        if (user != null) {
            user!!.getSessionInBackground(handler)
        } else {
            cb.onFailure(Exception("Login: user not set"))
        }
    }

    fun addLogin(provider: String, token: String) {
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
        async {
            credentialsProvider.refresh()
            Log.d("STATE", "IdentityID: " + credentialsProvider.identityId)
            Log.d("STATE", "Aws AccessID: " + credentialsProvider.credentials.awsAccessKeyId)
            Log.d("STATE", "Aws Secret: " + credentialsProvider.credentials.awsSecretKey)
            Log.d("STATE", "Token: " + credentialsProvider.credentials.sessionToken)
        }
    }

    interface ResetPwdHandler {
        fun onSuccess()
        fun onContinuation(continuation: ForgotPasswordContinuation)
        fun onFailure(ex: Exception)
    }

    fun resetPwd(username: String, cb: ResetPwdHandler) {
        if (user!!.userId !== username) {
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