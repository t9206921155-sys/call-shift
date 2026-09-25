package fi.callshift.app.ui

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import fi.callshift.app.CallShiftApp
import fi.callshift.app.databinding.ActivityDialerBinding
import fi.callshift.app.domain.ForwardResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Экран набора номера (обязателен для роли ROLE_DIALER: ACTION_DIAL).
 * Круглые клавиши с буквами, звук и вибрация нажатий, подсказка имени из контактов,
 * выбор SIM при нескольких картах, долгое нажатие: «0» → «+», номер → вставить из буфера.
 */
class DialerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDialerBinding
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }
    private val digits = StringBuilder()
    private var tones: Keypad.TonePlayer? = null
    private var lookupJob: Job? = null

    /** id SIM (PhoneAccountHandle) по id кнопки в переключателе. */
    private val simByButton = mutableMapOf<Int, String>()
    private val prefs by lazy { getSharedPreferences("dialer", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDialerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPad()
        setupSimToggle()
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        tones = Keypad.TonePlayer()
    }

    override fun onPause() {
        tones?.release()
        tones = null
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "tel" || uri.scheme == "voicemail") {
            val num = uri.schemeSpecificPart
            if (!num.isNullOrBlank()) {
                digits.clear()
                digits.append(num)
                updateDisplay()
            }
        }
    }

    private fun setupPad() {
        val b = binding
        Keypad.bind(
            listOf(
                b.k1 to '1', b.k2 to '2', b.k3 to '3', b.k4 to '4', b.k5 to '5', b.k6 to '6',
                b.k7 to '7', b.k8 to '8', b.k9 to '9', b.kStar to '*', b.k0 to '0', b.kHash to '#',
            ),
        ) { d ->
            tones?.play(d)
            appendDigit(d)
        }

        b.k0.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            appendDigit('+')
            true
        }

        b.btnBackspace.setOnClickListener {
            if (digits.isNotEmpty()) {
                digits.deleteCharAt(digits.length - 1)
                updateDisplay()
            }
        }
        b.btnBackspace.setOnLongClickListener {
            digits.clear()
            updateDisplay()
            true
        }

        // Долгое нажатие на номер — вставить из буфера обмена.
        b.tvNumber.setOnLongClickListener {
            val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            val cleaned = text?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
            if (!cleaned.isNullOrEmpty()) {
                digits.clear()
                digits.append(cleaned)
                updateDisplay()
                Toast.makeText(this, "Номер вставлен", Toast.LENGTH_SHORT).show()
            }
            true
        }

        b.btnCall.setOnClickListener {
            val num = digits.toString().trim()
            if (num.isEmpty()) {
                // Как в штатной звонилке: пустой номер → подставить последний набранный.
                prefs.getString(KEY_LAST_NUMBER, null)?.let {
                    digits.append(it)
                    updateDisplay()
                }
                return@setOnClickListener
            }
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            prefs.edit().putString(KEY_LAST_NUMBER, num).apply()
            val simId = simByButton[binding.simToggle.checkedButtonId]
            lifecycleScope.launch {
                val r = app.telecom.dial(num, simId)
                if (r is ForwardResult.Failed) {
                    Toast.makeText(this@DialerActivity, r.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        b.btnRules.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    /** Если SIM-карт несколько — показать переключатель «SIM 1 / SIM 2». */
    private fun setupSimToggle() {
        val accounts = app.telecom.phoneAccounts()
        if (accounts.size < 2) return
        val group = binding.simToggle
        group.visibility = View.VISIBLE
        val lastSim = prefs.getString(KEY_LAST_SIM, null)
        accounts.entries.forEachIndexed { i, (id, label) ->
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.id = View.generateViewId()
                text = "SIM ${i + 1} · ${label.ifBlank { id }}"
                isAllCaps = false
                setTextColor(android.graphics.Color.WHITE)
            }
            simByButton[btn.id] = id
            group.addView(btn)
            if (id == lastSim || (lastSim == null && i == 0)) group.check(btn.id)
        }
        if (group.checkedButtonId == View.NO_ID) group.check(group.getChildAt(0).id)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) simByButton[checkedId]?.let { prefs.edit().putString(KEY_LAST_SIM, it).apply() }
        }
    }

    private fun appendDigit(d: Char) {
        digits.append(d)
        updateDisplay()
    }

    private fun updateDisplay() {
        val text = digits.toString()
        binding.tvNumber.text = text
        binding.tvNumber.textSize = when {
            text.length > 16 -> 24f
            text.length > 12 -> 28f
            else -> 34f
        }
        binding.btnBackspace.visibility = if (text.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        lookupContact(text)
    }

    /** Подсказка имени из контактов для набранного номера. */
    private fun lookupContact(text: String) {
        lookupJob?.cancel()
        binding.tvContactHint.text = ""
        if (text.count { it.isDigit() } < 5) return
        lookupJob = lifecycleScope.launch {
            delay(250)
            val e164 = app.normalizer.normalize(text).e164 ?: return@launch
            val name = runCatching { app.contacts.contactName(e164) }.getOrNull()
            binding.tvContactHint.text = name.orEmpty()
        }
    }

    companion object {
        private const val KEY_LAST_NUMBER = "last_number"
        private const val KEY_LAST_SIM = "last_sim"
    }
}
