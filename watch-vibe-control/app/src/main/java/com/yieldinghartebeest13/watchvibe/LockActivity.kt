package com.yieldinghartebeest13.watchvibe

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import java.security.MessageDigest

class LockActivity : Activity() {

    private lateinit var pinBuilder: StringBuilder
    private lateinit var pinDots: List<View>
    private lateinit var errorText: TextView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        prefs = getSharedPreferences("stealth_prefs", MODE_PRIVATE)
        pinBuilder = StringBuilder()
        errorText = findViewById(R.id.lockErrorText)

        pinDots = listOf(
            findViewById(R.id.pinDot1),
            findViewById(R.id.pinDot2),
            findViewById(R.id.pinDot3),
            findViewById(R.id.pinDot4)
        )

        // Bind digit buttons (1-9, 0)
        val digitButtons = mapOf(
            R.id.keypad1 to "1", R.id.keypad2 to "2", R.id.keypad3 to "3",
            R.id.keypad4 to "4", R.id.keypad5 to "5", R.id.keypad6 to "6",
            R.id.keypad7 to "7", R.id.keypad8 to "8", R.id.keypad9 to "9",
            R.id.keypad0 to "0"
        )
        for ((id, digit) in digitButtons) {
            findViewById<View>(id).setOnClickListener {
                onDigitPressed(digit)
            }
        }

        findViewById<View>(R.id.keypadDelete).setOnClickListener {
            onDeletePressed()
        }
    }

    private fun onDigitPressed(digit: String) {
        if (pinBuilder.length >= 4) return
        errorText.visibility = View.GONE
        pinBuilder.append(digit)
        updateDots()
        if (pinBuilder.length == 4) {
            verifyPin()
        }
    }

    private fun onDeletePressed() {
        if (pinBuilder.isNotEmpty()) {
            pinBuilder.deleteCharAt(pinBuilder.length - 1)
            updateDots()
        }
    }

    private fun verifyPin() {
        val enteredPin = pinBuilder.toString()
        val storedHash = prefs.getString("pin_hash", null)
        if (storedHash == null) {
            // No PIN set — allow entry
            finish()
            return
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(enteredPin.toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (hash == storedHash) {
            finish()
        } else {
            errorText.visibility = View.VISIBLE
            pinBuilder.clear()
            updateDots()
        }
    }

    private fun updateDots() {
        for (i in 0 until 4) {
            if (i < pinBuilder.length) {
                pinDots[i].setBackgroundResource(R.drawable.pin_dot_filled)
            } else {
                pinDots[i].setBackgroundResource(R.drawable.pin_dot_empty)
            }
        }
    }

    override fun onBackPressed() {
        finishAffinity()
    }
}
