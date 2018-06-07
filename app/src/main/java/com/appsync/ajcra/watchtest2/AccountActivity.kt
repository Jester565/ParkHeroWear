package com.appsync.ajcra.watchtest2

import android.content.Intent
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.app.ActivityCompat.startActivityForResult
import android.support.wearable.activity.WearableActivity
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.ApiException

class AccountActivity : WearableActivity() {
    companion object {
        var RC_SIGN_IN: Int = 222
    }

    private lateinit var mGoogleSignInClient: GoogleSignInClient
    private lateinit var googleButton: SignInButton
    private lateinit var cognitoManager: CognitoManager

    fun initGoogleSignIn() {
        var gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken("484305592931-jk3fi0ton2brsatal9cb1mrnnc2ra04m.apps.googleusercontent.com")
                .build()
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    fun initGoogleButton() {
        googleButton.setOnClickListener {
            val signInIntent = mGoogleSignInClient.signInIntent
            startActivityForResult(signInIntent, RC_SIGN_IN)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)
        cognitoManager = CognitoManager.GetInstance(applicationContext)
        initGoogleSignIn()

        googleButton = findViewById(R.id.account_gbutton)
        initGoogleButton()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Result returned from launching the Intent from GoogleSignInClient.getSignInIntent(...);
        if (requestCode == RC_SIGN_IN) {
            // The Task returned from this call is always completed, no need to attach
            // a listener.
            val completedTask = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = completedTask.getResult(ApiException::class.java)
                var idToken = account.idToken
                if (idToken != null) {
                    cognitoManager.addLogin("accounts.google.com", idToken)
                    Log.d("STATE", "Login Complete")
                    //val intent = Intent(this, MainActivity::class.java)
                    //startActivity(intent)
                }
            } catch (e: ApiException) {
                Log.w("STATE", "signInResult:failed code=" + e.statusCode + ":" + e.message)
            }
        }
    }
}
