package com.appsync.ajcra.watchtest2

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.support.wearable.activity.WearableActivity
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.MultiFactorAuthenticationContinuation
import java.lang.Exception

class LoginActivity : WearableActivity() {
    private lateinit var usernameField: EditText
    private lateinit var pwdField: EditText
    private lateinit var loginButton: Button

    private lateinit var cognitoManager: CognitoManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        cognitoManager = CognitoManager(applicationContext)

        var textChangeListener = object: TextWatcher {
            override fun afterTextChanged(p0: Editable?) {

            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                loginButton.isEnabled = (usernameField.length() > 0 && pwdField.length() > 0)
                loginButton.setBackgroundResource(android.R.drawable.btn_default)
                loginButton.text = "Login"
            }
        }

        usernameField = findViewById(R.id.login_usernameField)
        pwdField = findViewById(R.id.login_pwdField)

        usernameField.addTextChangedListener(textChangeListener)
        pwdField.addTextChangedListener(textChangeListener)

        loginButton = findViewById(R.id.login_loginButton)

        loginButton.setOnClickListener {
            login()
        }
        setAmbientEnabled()
    }

    private fun login() {
        loginButton.isEnabled = false
        cognitoManager.login(usernameField.text.toString(), pwdField.text.toString(), object: CognitoManager.LoginHandler() {
            override fun onSuccess() {
                val intent = Intent(baseContext, MainActivity::class.java)
                startActivity(intent)
            }

            override fun onMFA(continuation: MultiFactorAuthenticationContinuation) {

            }

            override fun onFailure(ex: Exception) {
                loginButton.setBackgroundColor(Color.RED)
                loginButton.text = "ERROR"
            }

            override fun onUnverified(ex: Exception) {
                loginButton.setBackgroundColor(Color.RED)
                loginButton.text = "UNVERIFIED!"
            }
        })
    }
}
