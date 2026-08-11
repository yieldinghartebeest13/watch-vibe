package com.yieldinghartebeest13.watchvibe

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.security.MessageDigest

class LockActivity : Activity() {

    private lateinit var display: TextView
    private lateinit var prefs: SharedPreferences

    // Calculator state
    private var currentInput = "0"
    private var firstOperand = 0L
    private var pendingOp: Char = ' '
    private var clearOnNextDigit = false

    // PIN tracking: last N digits pressed
    private val digitRing = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_lock)

        prefs = getSharedPreferences("stealth_prefs", MODE_PRIVATE)
        display = findViewById(R.id.calcDisplay)

        // Digit buttons
        val digits = mapOf(
            R.id.key0 to '0', R.id.key1 to '1', R.id.key2 to '2',
            R.id.key3 to '3', R.id.key4 to '4', R.id.key5 to '5',
            R.id.key6 to '6', R.id.key7 to '7', R.id.key8 to '8',
            R.id.key9 to '9'
        )
        for ((id, digit) in digits) {
            findViewById<View>(id).setOnClickListener { onDigit(digit) }
        }

        // Operations
        findViewById<View>(R.id.keyAdd).setOnClickListener { onOp('+') }
        findViewById<View>(R.id.keySub).setOnClickListener { onOp('-') }
        findViewById<View>(R.id.keyMul).setOnClickListener { onOp('*') }
        findViewById<View>(R.id.keyDiv).setOnClickListener { onOp('/') }

        // Equals
        findViewById<View>(R.id.keyEq).setOnClickListener { onEquals() }

        // Clear
        findViewById<View>(R.id.keyClear).setOnClickListener { onClear() }
    }

    // ── Calculator logic ──────────────────────────────────

    private fun updateDisplay() {
        display.text = currentInput
    }

    private fun onDigit(digit: Char) {
        // Track digit for PIN unlock
        digitRing.append(digit)
        if (digitRing.length > 4) digitRing.delete(0, digitRing.length - 4)
        checkPin()

        if (clearOnNextDigit) {
            currentInput = "0"
            clearOnNextDigit = false
        }
        if (currentInput == "0") {
            currentInput = digit.toString()
        } else {
            currentInput += digit
        }
        updateDisplay()
    }

    private fun onOp(op: Char) {
        // Also check pin on operation press
        checkPin()

        val value = currentInput.toLongOrNull() ?: 0
        if (pendingOp != ' ') {
            firstOperand = compute(firstOperand, value, pendingOp)
        } else {
            firstOperand = value
        }
        pendingOp = op
        clearOnNextDigit = true
        currentInput = firstOperand.toString()
        updateDisplay()
    }

    private fun onEquals() {
        checkPin()

        val value = currentInput.toLongOrNull() ?: 0
        if (pendingOp != ' ') {
            val result = compute(firstOperand, value, pendingOp)
            currentInput = result.toString()
            firstOperand = 0
            pendingOp = ' '
        }
        clearOnNextDigit = true
        updateDisplay()
    }

    private fun onClear() {
        currentInput = "0"
        firstOperand = 0
        pendingOp = ' '
        clearOnNextDigit = false
        updateDisplay()
    }

    private fun compute(a: Long, b: Long, op: Char): Long {
        return when (op) {
            '+' -> a + b
            '-' -> a - b
            '*' -> a * b
            '/' -> if (b != 0L) a / b else 0
            else -> b
        }
    }

    // ── PIN unlock ────────────────────────────────────────

    private fun checkPin() {
        if (digitRing.length != 4) return
        val storedHash = prefs.getString("pin_hash", null) ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(digitRing.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (hash == storedHash) {
            finish()
        }
    }

    override fun onBackPressed() {
        finishAffinity()
    }
}
