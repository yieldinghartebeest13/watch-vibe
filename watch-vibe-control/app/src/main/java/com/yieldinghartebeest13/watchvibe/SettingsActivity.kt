package com.yieldinghartebeest13.watchvibe

import android.app.AlertDialog
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.security.MessageDigest

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var stealthSwitch: SwitchCompat
    private lateinit var pinStatusText: TextView
    private lateinit var setPinButton: Button
    private lateinit var pinSection: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("stealth_prefs", MODE_PRIVATE)

        // Back button
        findViewById<TextView>(R.id.settingsBackBtn).setOnClickListener { finish() }

        // Bind views
        stealthSwitch = findViewById(R.id.stealthSwitch)
        pinStatusText = findViewById(R.id.pinStatusText)
        setPinButton = findViewById(R.id.setPinButton)
        pinSection = findViewById(R.id.pinSection)

        // Load current state
        val stealthEnabled = prefs.getBoolean("stealth_enabled", false)
        stealthSwitch.isChecked = stealthEnabled
        updatePinSection()

        // Listeners
        stealthSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("stealth_enabled", isChecked).apply()
            updatePinSection()
            if (isChecked && prefs.getString("pin_hash", null) == null) {
                showPinDialog()
            } else {
                // Pin already set — safe to apply alias change immediately
                toggleAlias(isChecked)
            }
        }

        setPinButton.setOnClickListener { showPinDialog() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Apply any pending alias change (covers the case where pin was
        // set after stealth was enabled and alias hasn't been toggled yet)
        val stealthEnabled = prefs.getBoolean("stealth_enabled", false)
        toggleAlias(stealthEnabled)
    }

    private fun toggleAlias(enabled: Boolean) {
        val pm = packageManager
        val defaultAlias = ComponentName(this, "${packageName}.MainActivityDefault")
        val stealthAlias = ComponentName(this, "${packageName}.MainActivityStealth")
        pm.setComponentEnabledSetting(
            defaultAlias,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            stealthAlias,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun showPinDialog() {
        // First prompt: Enter new PIN
        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val firstDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Enter new PIN")
            .setView(pinInput)
            .setPositiveButton("Next", null) // set later to prevent auto-dismiss
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()

        firstDialog.setOnShowListener {
            firstDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = pinInput.text.toString()
                if (pin.length != 4) {
                    Toast.makeText(this, "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Second prompt: Confirm PIN
                val confirmInput = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    filters = arrayOf(InputFilter.LengthFilter(4))
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }

                val secondDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Confirm PIN")
                    .setView(confirmInput)
                    .setPositiveButton("OK", null)
                    .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                    .create()

                secondDialog.setOnShowListener {
                    secondDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val confirmPin = confirmInput.text.toString()
                        if (confirmPin.length != 4) {
                            Toast.makeText(this, "PIN must be 4 digits", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        if (pin == confirmPin) {
                            val hash = hashPin(pin)
                            prefs.edit().putString("pin_hash", hash).apply()
                            pinStatusText.text = "••••"
                            setPinButton.text = "Change PIN"
                            firstDialog.dismiss()
                            secondDialog.dismiss()
                        } else {
                            Toast.makeText(this, "PINs do not match", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                secondDialog.show()
            }
        }

        firstDialog.show()
    }

    private fun updatePinSection() {
        val stealthEnabled = prefs.getBoolean("stealth_enabled", false)
        pinSection.visibility = if (stealthEnabled) LinearLayout.VISIBLE else LinearLayout.GONE

        val pinHash = prefs.getString("pin_hash", null)
        if (pinHash != null) {
            pinStatusText.text = "••••"
            setPinButton.text = "Change PIN"
        } else {
            pinStatusText.text = "Not set"
            setPinButton.text = "Set PIN"
        }
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
