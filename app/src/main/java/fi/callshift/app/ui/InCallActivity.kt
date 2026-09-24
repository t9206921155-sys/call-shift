package fi.callshift.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import fi.callshift.app.databinding.ActivityInCallBinding
import fi.callshift.app.databinding.ItemSecondaryCallBinding
import fi.callshift.app.telecom.InCallController

/**
 * Экран входящего и активного вызова (ТЗ FR-P3: обязательное требование роли
 * ROLE_DIALER — «both an incoming call UI and an ongoing call UI»).
 *
 * Поведение:
 *  - показывается поверх блокировки экрана и включает экран (setShowWhenLocked /
 *    setTurnScreenOn, API 27+);
 *  - состояние берёт из [InCallController] (синглтон процесса), поэтому
 *    пересоздание Activity не теряет вызовы;
 *  - когда вызовов не осталось — закрывается;
 *  - кнопки меняются по состоянию: incoming → «Ответить»/«Отклонить»,
 *    active → «Завершить» + удержание/динамик/микрофон/DTMF.
 */
class InCallActivity : AppCompatActivity(), InCallController.Listener {

    private lateinit var binding: ActivityInCallBinding
    private val controller = InCallController.get()
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }

    private var current: List<InCallController.CallInfo> = emptyList()
    private var keypadVisible = false
    private var speakerOn = false
    private var muted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLockScreenFlags()
        setupButtons()
        setupDtmfPad()
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
        binding.btnAnswer.setOnClickListener { primary()?.let { controller.answer(it.id) } }
        binding.btnReject.setOnClickListener {
            val p = primary() ?: return@setOnClickListener
            if (p.isRinging) controller.reject(p.id) else controller.disconnect(p.id)
        }
        binding.btnHold.setOnClickListener {
            val p = primary() ?: return@setOnClickListener
            if (p.isOnHold) controller.unhold(p.id) else controller.hold(p.id)
        }
        binding.btnSpeaker.setOnClickListener {
            val p = primary() ?: return@setOnClickListener
            speakerOn = !speakerOn
            controller.setSpeakerphone(p.id, speakerOn)
            binding.btnSpeaker.alpha = if (speakerOn) 1f else 0.55f
        }
        binding.btnMute.setOnClickListener {
            muted = !muted
            controller.setMute(muted)
            binding.btnMute.alpha = if (muted) 1f else 0.55f
        }
        binding.btnKeypad.setOnClickListener {
            keypadVisible = !keypadVisible
            binding.dtmfPad.visibility = if (keypadVisible) View.VISIBLE else View.GONE
        }
        binding.btnSpeaker.alpha = 0.55f
        binding.btnMute.alpha = 0.55f
    }

    private fun setupDtmfPad() {
        val keys = listOf(
            binding.d1 to '1', binding.d2 to '2', binding.d3 to '3',
            binding.d4 to '4', binding.d5 to '5', binding.d6 to '6',
            binding.d7 to '7', binding.d8 to '8', binding.d9 to '9',
            binding.dStar to '*', binding.d0 to '0', binding.dHash to '#',
        )
        keys.forEach { (button, digit) ->
            button.setOnClickListener {
                primary()?.let { call -> controller.playDtmf(call.id, digit) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.addListener(this)
    }

    override fun onStop() {
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
        binding.tvCallName.text = info.name ?: getString(R.string.unknown)
        binding.tvCallNumber.text = if (app.settings.maskNumbersInUi) {
            app.normalizer.mask(info.number)
        } else {
            info.number ?: getString(R.string.unknown)
        }
        binding.tvCallState.text = buildString {
            append(info.stateLabel.uppercase())
            if (info.connectTimeMs > 0) append(" · ${formatDuration(info.connectTimeMs)}")
            info.simLabel?.let { append(" · $it") }
        }

        // Подсказка о переадресации: если это наш дозвон на цель — показываем,
        // чей звонок мы перенаправили (ТЗ FR-4.6, п. 10.6).
        val dtmf = controller.pendingDtmf
        if (dtmf != null && info.number != null && info.number.filter(Char::isDigit)
                .endsWith(dtmf.targetNumber.filter(Char::isDigit).takeLast(7))
        ) {
            binding.tvForwardHint.visibility = View.VISIBLE
            binding.tvForwardHint.text = getString(R.string.incall_forward_hint, dtmf.originalNumber)
        } else {
            binding.tvForwardHint.visibility = View.GONE
        }

        val ringing = info.isRinging
        binding.btnAnswer.visibility = if (ringing) View.VISIBLE else View.GONE
        binding.btnReject.text = getString(if (ringing) R.string.incall_reject else R.string.incall_hangup)
        binding.btnHold.isEnabled = info.isActive || info.isOnHold
        binding.btnHold.text = getString(if (info.isOnHold) R.string.incall_unhold else R.string.incall_hold)
        binding.btnSpeaker.isEnabled = info.isActive
        binding.btnMute.isEnabled = info.isActive
        binding.btnKeypad.isEnabled = info.isActive
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
            item.tvSecondary.text = getString(
                R.string.incall_secondary_format,
                info.number ?: getString(R.string.unknown),
                info.stateLabel,
            )
            item.btnSwap.setOnClickListener {
                // «Swap» — делаем этот вызов основным: снимаем его с удержания,
                // основной ставим на удержание.
                primary()?.let { p -> controller.hold(p.id) }
                controller.unhold(info.id)
            }
            binding.secondaryCalls.addView(item.root)
        }
    }

    private fun primary(): InCallController.CallInfo? =
        current.firstOrNull { it.isRinging } ?: current.firstOrNull()

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return "%02d:%02d".format(m, s)
    }

    companion object {
        const val EXTRA_NUMBER = "extra_number"

        fun start(context: Context) {
            context.startActivity(
                Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
