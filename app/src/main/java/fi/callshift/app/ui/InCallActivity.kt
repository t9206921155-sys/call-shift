package fi.callshift.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import fi.callshift.app.databinding.ActivityInCallBinding
import fi.callshift.app.databinding.ItemSecondaryCallBinding
import fi.callshift.app.telecom.InCallController
import kotlinx.coroutines.launch

/**
 * Экран входящего и активного вызова (обязателен для роли ROLE_DIALER).
 *
 *  - поверх блокировки, включает экран;
 *  - входящий: «Отклонить» / «SMS» (отклонить + быстрый ответ) / «Ответить»;
 *  - разговор: «Без звука», «Клавиатура», «Динамик», «Удержать», красная «Завершить»;
 *  - живой таймер разговора, имя из контактов и аватар с инициалами, SIM-чип;
 *  - вторая линия/удержание — карточки сверху с кнопкой «Переключить».
 */
class InCallActivity : AppCompatActivity(), InCallController.Listener {

    private lateinit var binding: ActivityInCallBinding
    private val controller = InCallController.get()
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }

    private var current: List<InCallController.CallInfo> = emptyList()
    private var keypadVisible = false
    private var speakerOn = false
    private var muted = false
    private val dtmfTyped = StringBuilder()

    /** Кэш имён из контактов: номер → имя. */
    private val names = mutableMapOf<String, String?>()

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            primary()?.let { renderState(it) }
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLockScreenFlags()
        setupButtons()
        setupDtmfPad()
        setupQuickReplies()
    }

    private fun setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            runCatching {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.requestDismissKeyguard(this, null)
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupButtons() {
        binding.btnAnswer.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            primary()?.let { p -> controller.answer(p.id) }
        }
        binding.btnReject.setOnClickListener { primary()?.let { p -> controller.reject(p.id) } }
        binding.btnHangup.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            primary()?.let { p -> controller.disconnect(p.id) }
        }
        binding.btnSms.setOnClickListener {
            val show = binding.quickReplies.visibility != View.VISIBLE
            binding.quickReplies.visibility = if (show) View.VISIBLE else View.GONE
            Keypad.setToggle(binding.btnSms, show)
        }
        binding.btnHold.setOnClickListener {
            val p = primary() ?: return@setOnClickListener
            if (p.isOnHold) controller.unhold(p.id) else controller.hold(p.id)
        }
        binding.btnSpeaker.setOnClickListener {
            val p = primary() ?: return@setOnClickListener
            speakerOn = !speakerOn
            controller.setSpeakerphone(p.id, speakerOn)
            Keypad.setToggle(binding.btnSpeaker, speakerOn)
        }
        binding.btnMute.setOnClickListener {
            muted = !muted
            controller.setMute(muted)
            Keypad.setToggle(binding.btnMute, muted)
        }
        binding.btnKeypad.setOnClickListener {
            keypadVisible = !keypadVisible
            binding.dtmfPad.visibility = if (keypadVisible) View.VISIBLE else View.GONE
            // Клавиатуре нужно место — прячем аватар, имя остаётся.
            binding.avatarFrame.visibility = if (keypadVisible) View.GONE else View.VISIBLE
            binding.lblKeypad.text = if (keypadVisible) "Скрыть" else "Клавиатура"
            Keypad.setToggle(binding.btnKeypad, keypadVisible)
        }
        listOf(binding.btnMute, binding.btnSpeaker, binding.btnHold, binding.btnKeypad, binding.btnSms)
            .forEach { Keypad.setToggle(it, false) }
    }

    private fun setupDtmfPad() {
        val b = binding
        Keypad.bind(
            listOf(
                b.d1 to '1', b.d2 to '2', b.d3 to '3', b.d4 to '4', b.d5 to '5', b.d6 to '6',
                b.d7 to '7', b.d8 to '8', b.d9 to '9', b.dStar to '*', b.d0 to '0', b.dHash to '#',
            ),
        ) { digit ->
            primary()?.let { call -> controller.playDtmf(call.id, digit) }
            dtmfTyped.append(digit)
            b.tvDtmfDigits.text = dtmfTyped
        }
    }

    private fun setupQuickReplies() {
        val replies = QUICK_REPLIES + "Свой текст…"
        val inflater = LayoutInflater.from(this)
        replies.forEachIndexed { i, text ->
            val tv = inflater.inflate(android.R.layout.simple_list_item_1, binding.quickRepliesList, false) as TextView
            tv.text = text
            tv.setTextColor(if (i == QUICK_REPLIES.size) 0xFF7FE0AE.toInt() else 0xFFFFFFFF.toInt())
            tv.textSize = 15f
            tv.setBackgroundResource(android.R.drawable.list_selector_background)
            tv.setOnClickListener {
                if (i == QUICK_REPLIES.size) askCustomReply() else rejectWithSms(text)
            }
            binding.quickRepliesList.addView(tv)
        }
    }

    private fun askCustomReply() {
        val input = EditText(this).apply {
            hint = "Текст SMS"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle("Отклонить и отправить SMS")
            .setView(input)
            .setPositiveButton("Отправить") { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(::rejectWithSms)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Отклонить входящий и отправить SMS звонящему с той же SIM. */
    private fun rejectWithSms(text: String) {
        val p = primary() ?: return
        controller.reject(p.id)
        val error = app.smsReplier.sendQuickReply(p.number, text, p.simId)
        Toast.makeText(
            applicationContext,
            error ?: "SMS отправлено: «$text»",
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onStart() {
        super.onStart()
        controller.addListener(this)
        ticker.post(tick)
    }

    override fun onStop() {
        ticker.removeCallbacks(tick)
        controller.removeListener(this)
        super.onStop()
    }

    override fun onCallsChanged(calls: List<InCallController.CallInfo>) {
        current = calls
        if (calls.isEmpty()) {
            finish()
            return
        }
        val primary = calls.firstOrNull { it.isRinging } ?: calls.first()
        renderPrimary(primary)
        renderSecondary(calls.filter { it.id != primary.id })
    }

    private fun renderPrimary(info: InCallController.CallInfo) {
        val shownNumber = if (app.settings.maskNumbersInUi) app.normalizer.mask(info.number) else info.number
        val contactName = info.name?.takeIf { it.isNotBlank() } ?: info.number?.let { names[it] }
        if (info.number != null && !names.containsKey(info.number)) lookupName(info.number)

        binding.tvCallName.text = contactName ?: shownNumber ?: "Скрытый номер"
        binding.tvCallNumber.text = if (contactName != null) shownNumber.orEmpty() else ""
        binding.tvCallNumber.visibility = if (binding.tvCallNumber.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        val initials = Keypad.initials(contactName)
        binding.tvAvatar.text = initials.orEmpty()
        binding.tvAvatar.visibility = if (initials != null) View.VISIBLE else View.GONE
        binding.ivAvatar.visibility = if (initials != null) View.GONE else View.VISIBLE

        val sim = info.simLabel ?: info.simId?.let { app.telecom.phoneAccounts()[it] }
        val simCount = app.telecom.phoneAccounts().size
        if (sim != null && simCount > 1) {
            val idx = app.telecom.phoneAccounts().keys.indexOf(info.simId)
            binding.tvSimChip.text = if (idx >= 0) "SIM ${idx + 1} · $sim" else sim
            binding.tvSimChip.visibility = View.VISIBLE
        } else {
            binding.tvSimChip.visibility = View.GONE
        }

        // Подсказка: это наш дозвон на цель — чей звонок мы перенаправили.
        val dtmf = controller.pendingDtmf
        if (dtmf != null && info.number != null && info.number.filter(Char::isDigit)
                .endsWith(dtmf.targetNumber.filter(Char::isDigit).takeLast(7))
        ) {
            binding.tvForwardHint.visibility = View.VISIBLE
            binding.tvForwardHint.text = getString(R.string.incall_forward_hint, dtmf.originalNumber)
        } else {
            binding.tvForwardHint.visibility = View.GONE
        }

        renderState(info)

        val ringing = info.isRinging
        binding.incomingRow.visibility = if (ringing) View.VISIBLE else View.GONE
        binding.activeRow.visibility = if (ringing) View.GONE else View.VISIBLE
        binding.controlsRow.visibility = if (ringing) View.INVISIBLE else View.VISIBLE
        if (!ringing) {
            binding.quickReplies.visibility = View.GONE
        }
        if (!ringing && keypadVisible.not()) binding.dtmfPad.visibility = View.GONE

        val canControl = info.isActive || info.isOnHold
        setEnabled(binding.btnHold, canControl)
        setEnabled(binding.btnSpeaker, !ringing)
        setEnabled(binding.btnMute, info.isActive)
        setEnabled(binding.btnKeypad, info.isActive)
        Keypad.setToggle(binding.btnHold, info.isOnHold)
        binding.lblHold.text = if (info.isOnHold) "Вернуть" else "Удержать"
    }

    private fun renderState(info: InCallController.CallInfo) {
        binding.tvCallState.text = when {
            info.isRinging -> "Входящий вызов"
            info.isOnHold -> "На удержании" + duration(info)
            info.isActive -> duration(info).removePrefix(" · ").ifEmpty { "Разговор" }
            info.isOutgoing -> "Вызов…"
            else -> info.stateLabel.replaceFirstChar { it.uppercase() }
        }
    }

    private fun duration(info: InCallController.CallInfo): String {
        if (info.connectTimeMs <= 0) return ""
        val total = ((System.currentTimeMillis() - info.connectTimeMs) / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return " · " + if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.35f
    }

    private fun lookupName(number: String) {
        names[number] = null
        lifecycleScope.launch {
            val e164 = app.normalizer.normalize(number).e164 ?: number
            val name = runCatching { app.contacts.contactName(e164) }.getOrNull()
            if (name != null) {
                names[number] = name
                primary()?.let { renderPrimary(it) }
            }
        }
    }

    private fun renderSecondary(others: List<InCallController.CallInfo>) {
        binding.secondaryCalls.removeAllViews()
        if (others.isEmpty()) {
            binding.secondaryCalls.visibility = View.GONE
            return
        }
        binding.secondaryCalls.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        others.forEach { info ->
            val item = ItemSecondaryCallBinding.inflate(inflater, binding.secondaryCalls, false)
            val who = info.number?.let { names[it] } ?: info.number ?: "Скрытый номер"
            item.tvSecondary.text = getString(R.string.incall_secondary_format, who, info.stateLabel)
            item.btnSwap.setOnClickListener {
                primary()?.let { p -> controller.hold(p.id) }
                controller.unhold(info.id)
            }
            binding.secondaryCalls.addView(item.root)
        }
    }

    private fun primary(): InCallController.CallInfo? =
        current.firstOrNull { it.isRinging } ?: current.firstOrNull()

    companion object {
        const val EXTRA_NUMBER = "extra_number"

        val QUICK_REPLIES = listOf(
            "Не могу говорить, перезвоню позже",
            "Перезвоню через 10 минут",
            "Я за рулём, перезвоню",
            "Напишите, пожалуйста, SMS",
        )

        fun start(context: Context) {
            context.startActivity(
                Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
