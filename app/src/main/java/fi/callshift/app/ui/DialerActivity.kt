package fi.callshift.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fi.callshift.app.CallShiftApp
import fi.callshift.app.databinding.ActivityDialerBinding
import kotlinx.coroutines.launch

/**
 * Экран набора номера (ТЗ FR-P3, обязательное требование роли ROLE_DIALER:
 * «It must handle the Intent#ACTION_DIAL intent. This means the app must
 * provide a dial pad UI for the user to initiate outgoing calls»).
 */
class DialerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDialerBinding
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }
    private val digits = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDialerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPad()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val scheme = uri.scheme
        if (scheme == "tel" || scheme == "voicemail") {
            val num = uri.schemeSpecificPart
            if (!num.isNullOrBlank()) {
                digits.clear()
                digits.append(num)
                updateDisplay()
            }
        }
    }

    private fun setupPad() {
        val keys = listOf(
            binding.k1 to '1', binding.k2 to '2', binding.k3 to '3',
            binding.k4 to '4', binding.k5 to '5', binding.k6 to '6',
            binding.k7 to '7', binding.k8 to '8', binding.k9 to '9',
            binding.kStar to '*', binding.k0 to '0', binding.kHash to '#',
        )

        keys.forEach { (button, digit) ->
            button.setOnClickListener { appendDigit(digit) }
        }

        // Долгое нажатие '0' вводит '+'
        binding.k0.setOnLongClickListener {
            appendDigit('+')
            true
        }

        binding.btnBackspace.setOnClickListener {
            if (digits.isNotEmpty()) {
                digits.deleteCharAt(digits.length - 1)
                updateDisplay()
            }
        }

        binding.btnBackspace.setOnLongClickListener {
            digits.clear()
            updateDisplay()
            true
        }

        binding.btnCall.setOnClickListener {
            val num = digits.toString().trim()
            if (num.isNotEmpty()) {
                lifecycleScope.launch {
                    app.telecom.dial(num)
                }
            }
        }

        binding.btnRules.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun appendDigit(d: Char) {
        digits.append(d)
        updateDisplay()
    }

    private fun updateDisplay() {
        binding.tvNumber.text = digits.toString()
        binding.btnBackspace.visibility = if (digits.isNotEmpty()) View.VISIBLE else View.INVISIBLE
    }
}
