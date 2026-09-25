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
            if (CallRecorder.isRecording) renderRecording()
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CallBackground.apply(this, binding.root)
        binding.avatarFrame.clipToOutline = true

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
        binding.swipeCall.onAnswer = { primary()?.let { p -> controller.answer(p.id) } }
        binding.swipeCall.onReject = { primary()?.let { p -> controller.reject(p.id) } }
        binding.btnSilence.setOnClickListener {
            runCatching { getSystemService(android.telecom.TelecomManager::class.java)?.silenceRinger() }
            Keypad.setToggle(binding.btnSilence, true)
            binding.lblSilence.text = "Тихо"
        }
        binding.btnRemind.setOnClickListener { askReminder() }
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
            // Как в штатной звонилке: если подключены наушники/Bluetooth — выбор, куда выводить звук.
            val (mask, _) = controller.audioRoutes()
            val extra = android.telecom.CallAudioState.ROUTE_BLUETOOTH or android.telecom.CallAudioState.ROUTE_WIRED_HEADSET
            if (mask and extra != 0) { showAudioRoutes(); return@setOnClickListener }
            speakerOn = !speakerOn
            controller.setSpeakerphone(p.id, speakerOn)
            Keypad.setToggle(binding.btnSpeaker, speakerOn)
        }
        binding.btnSpeaker.setOnLongClickListener { showAudioRoutes(); true }
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
            binding.lblKeypad.text = if (keypadVisible) "Скрыть" else "Панель набора"
            Keypad.setToggle(binding.btnKeypad, keypadVisible)
        }
        binding.btnAddCall.setOnClickListener {
            // Текущий звонок система поставит на удержание при наборе второго.
            startActivity(Intent(this, DialerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnVideo.setOnClickListener {
            val p = primary()
            android.widget.Toast.makeText(this,
                if (p?.canVideo == true) "Видеозвонок в CallShift пока не поддерживается — используйте штатную звонилку"
                else "Видеозвонок недоступен: оператор или собеседник не поддерживает ViLTE",
                android.widget.Toast.LENGTH_LONG).show()
        }
        binding.btnNotes.setOnClickListener { showNotes() }
        listOf(binding.btnMute, binding.btnSpeaker, binding.btnHold, binding.btnKeypad, binding.btnSms, binding.btnAddCall, binding.btnRemind, binding.btnSilence,
            binding.btnRecord, binding.btnVideo, binding.btnNotes)
            .forEach { Keypad.setToggle(it, false) }
    }

    private fun showAudioRoutes() {
        val (mask, current) = controller.audioRoutes()
        val all = listOf(
            android.telecom.CallAudioState.ROUTE_EARPIECE to "📱 Телефон",
            android.telecom.CallAudioState.ROUTE_SPEAKER to "🔊 Динамик",
            android.telecom.CallAudioState.ROUTE_BLUETOOTH to "🎧 Bluetooth",
            android.telecom.CallAudioState.ROUTE_WIRED_HEADSET to "🎧 Проводная гарнитура",
        ).filter { mask == 0 || mask and it.first != 0 }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Куда выводить звук")
            .setSingleChoiceItems(all.map { it.second }.toTypedArray(), all.indexOfFirst { it.first == current }) { d, i ->
                controller.setAudioRoute(all[i].first)
                speakerOn = all[i].first == android.telecom.CallAudioState.ROUTE_SPEAKER
                Keypad.setToggle(binding.btnSpeaker, speakerOn)
                d.dismiss()
            }
            .show()
    }

    // ---------------- Напомнить позже ----------------

    private fun askReminder() {
        val p = primary() ?: return
        val number = p.number
        if (number.isNullOrBlank()) {
            Toast.makeText(this, "Номер скрыт — напоминание невозможно", Toast.LENGTH_SHORT).show(); return
        }
        val name = p.name?.takeIf { it.isNotBlank() } ?: names[number]
        val opts = listOf("Через 10 минут" to 10L, "Через 30 минут" to 30L, "Через 1 час" to 60L, "Через 3 часа" to 180L)
        AlertDialog.Builder(this)
            .setTitle("Отклонить и напомнить")
            .setItems(opts.map { it.first }.toTypedArray()) { _, i ->
                CallReminderReceiver.schedule(this, number, name, opts[i].second * 60_000L)
                controller.reject(p.id)
                Toast.makeText(applicationContext, "Напомню перезвонить: ${opts[i].first.lowercase()}", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------- Фото контакта ----------------

    private val photos = mutableMapOf<String, android.graphics.Bitmap?>()

    private fun loadPhoto(number: String) {
        photos[number] = null
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        lifecycleScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val uri = android.net.Uri.withAppendedPath(
                        android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
                    val photoUri = contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.PHOTO_URI),
                        null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@runCatching null
                    contentResolver.openInputStream(android.net.Uri.parse(photoUri))?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
            }
            if (bmp != null) {
                photos[number] = bmp
                primary()?.let { renderPrimary(it) }
            }
        }
    }

    // ---------------- Запись ----------------

    private val askMic = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) toggleRecording()
        else android.widget.Toast.makeText(this, "Для записи нужен доступ к микрофону", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun toggleRecording() {
        if (CallRecorder.isRecording) {
            val f = CallRecorder.stop()
            android.widget.Toast.makeText(this, if (f != null) "Запись сохранена: ${f.name}" else "Запись не сохранилась",
                android.widget.Toast.LENGTH_LONG).show()
            renderRecording()
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { askMic.launch(android.Manifest.permission.RECORD_AUDIO); return }
        val prefs = getSharedPreferences("recorder", MODE_PRIVATE)
        if (!prefs.getBoolean("warned", false)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Запись разговора")
                .setMessage("Android не даёт сторонним звонилкам записывать линию напрямую, поэтому запись идёт с микрофона: " +
                    "ваш голос слышно всегда, собеседника — хорошо при включённом динамике.\n\n" +
                    "Записи: «⋮» → «Записи разговоров» в звонилке. Предупреждайте собеседника о записи.")
                .setPositiveButton("Начать") { _, _ -> prefs.edit().putBoolean("warned", true).apply(); toggleRecording() }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        CallRecorder.start(this, primary()?.number)
            .onFailure {
                android.widget.Toast.makeText(this, "Не удалось начать запись: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        renderRecording()
    }

    private fun renderRecording() {
        val on = CallRecorder.isRecording
        Keypad.setToggle(binding.btnRecord, on)
        binding.lblRecord.text = if (on) {
            val sec = (System.currentTimeMillis() - CallRecorder.startedAt) / 1000
            "● %d:%02d".format(sec / 60, sec % 60)
        } else "Запись"
        binding.lblRecord.setTextColor(if (on) 0xFFFF5252.toInt() else 0xFFD8E7DE.toInt())
    }

    // ---------------- Примечания ----------------

    private fun showNotes() {
        val p = primary()
        val number = p?.number
        val name = p?.name?.takeIf { it.isNotBlank() } ?: number?.let { names[it] }
        val input = android.widget.EditText(this).apply {
            hint = "Текст примечания"
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        val old = CallNotes.forNumber(this, number).take(5)
        val df = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            if (old.isNotEmpty()) addView(android.widget.TextView(context).apply {
                text = "Прошлые примечания:\n" + old.joinToString("\n") { "• ${df.format(java.util.Date(it.ts))}: ${it.text}" }
                setPadding(0, 0, 0, pad / 2)
            })
            addView(input)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Примечание — ${name ?: number ?: "звонок"}")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    CallNotes.add(this, CallNotes.Note(System.currentTimeMillis(), number, name, t))
                    android.widget.Toast.makeText(this, "Примечание сохранено", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // Пульсация аватара во время входящего, как в штатной звонилке.
    private var ringPulse: android.animation.ObjectAnimator? = null

    private fun startRingPulse() {
        if (ringPulse != null) return
        ringPulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(binding.avatarFrame,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f)).apply {
            duration = 700; repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopRingPulse() {
        ringPulse?.cancel(); ringPulse = null
        binding.avatarFrame.scaleX = 1f; binding.avatarFrame.scaleY = 1f
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
            error ?: "SMS передана Android. Отправка и доставка не подтверждены.",
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
            CallRecorder.stop()?.let {
                android.widget.Toast.makeText(this, "Запись сохранена: ${it.name}", android.widget.Toast.LENGTH_LONG).show()
            }
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

        if (info.number != null && !photos.containsKey(info.number)) loadPhoto(info.number)
        val photo = info.number?.let { photos[it] }
        val initials = Keypad.initials(contactName)
        binding.ivPhoto.setImageBitmap(photo)
        binding.ivPhoto.visibility = if (photo != null) View.VISIBLE else View.GONE
        binding.tvAvatar.text = initials.orEmpty()
        binding.tvAvatar.visibility = if (photo == null && initials != null) View.VISIBLE else View.GONE
        binding.ivAvatar.visibility = if (photo == null && initials == null) View.VISIBLE else View.GONE

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
        if (ringing) startRingPulse() else { stopRingPulse(); binding.swipeCall.reset() }
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
        setEnabled(binding.btnAddCall, info.isActive || info.isOnHold)
        setEnabled(binding.btnRecord, info.isActive || CallRecorder.isRecording)
        setEnabled(binding.btnVideo, info.canVideo)
        setEnabled(binding.btnNotes, true)
        renderRecording()
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
        // Как в штатной звонилке: недоступная кнопка тускнеет вместе с подписью.
        ((v.parent as? View) ?: v).alpha = if (enabled) 1f else 0.4f
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
