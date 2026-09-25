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
import fi.callshift.app.data.PhoneBook
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.text.format.DateUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
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
    private val book by lazy { PhoneBook(this) }
    private var t9Job: Job? = null
    private var listJob: Job? = null
    private var recents: List<PhoneBook.Recent> = emptyList()
    private lateinit var t9Adapter: DialEntryAdapter
    private lateinit var listAdapter: DialEntryAdapter

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshList(reload = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDialerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPad()
        setupSimToggle()
        setupLists()
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        tones = Keypad.TonePlayer()
        if (binding.panelList.visibility == View.VISIBLE) refreshList(reload = true)
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
        if (intent?.getBooleanExtra(EXTRA_SHOW_RECENTS, false) == true) binding.tabs.check(binding.tabRecents.id)
        val uri = intent?.data ?: return
        if (intent.getBooleanExtra(EXTRA_AUTODIAL, false) && uri.scheme == "tel") {
            uri.schemeSpecificPart?.takeIf { it.isNotBlank() }?.let { placeCall(it) }
            return
        }
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
            placeCall(num)
        }

        b.btnRules.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun placeCall(num: String) {
        prefs.edit().putString(KEY_LAST_NUMBER, num).apply()
        val simId = simByButton[binding.simToggle.checkedButtonId] ?: prefs.getString(KEY_LAST_SIM, null)
        lifecycleScope.launch {
            val r = app.telecom.dial(num, simId)
            if (r is ForwardResult.Failed) {
                Toast.makeText(this@DialerActivity, r.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------- Вкладки, T9, списки ----------------

    private fun setupLists() {
        t9Adapter = DialEntryAdapter(onCall = { placeCall(it.number) }, onLong = ::showEntryMenu, onClick = {
            digits.clear(); digits.append(it.number.filter { c -> c.isDigit() || c == '+' }); updateDisplay()
        })
        binding.listT9.layoutManager = LinearLayoutManager(this)
        binding.listT9.adapter = t9Adapter
        listAdapter = DialEntryAdapter(onCall = { placeCall(it.number) }, onLong = ::showEntryMenu)
        binding.listItems.layoutManager = LinearLayoutManager(this)
        binding.listItems.adapter = listAdapter
        binding.tabs.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val pad = id == binding.tabPad.id
            binding.panelPad.visibility = if (pad) View.VISIBLE else View.GONE
            binding.panelList.visibility = if (pad) View.GONE else View.VISIBLE
            if (!pad) {
                binding.etSearch.setText("")
                refreshList(reload = recents.isEmpty())
                ensurePerms()
            }
        }
        binding.etSearch.doAfterTextChanged { refreshList(reload = false) }
    }

    private fun ensurePerms() {
        val need = listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) askPerms.launch(need.toTypedArray())
    }

    private fun refreshList(reload: Boolean) {
        listJob?.cancel()
        val q = binding.etSearch.text?.toString()?.trim().orEmpty()
        val showRecents = binding.tabs.checkedButtonId == binding.tabRecents.id
        listJob = lifecycleScope.launch {
            if (!reload) delay(120)
            val entries = if (showRecents) {
                if (reload || recents.isEmpty()) {
                    recents = book.recents()
                    MissedCallReceiver.cancel(this@DialerActivity)
                }
                recents.filter { matches(q, it.name, it.number) }.map(::recentEntry)
            } else {
                book.contacts(refresh = reload).filter { matches(q, it.name, it.number) }.map {
                    DialEntry((if (it.starred) "★ " else "") + it.name, "${it.label} · ${it.number}", it.number, it.name)
                }
            }
            listAdapter.items = entries
            val empty = when {
                showRecents && !book.canReadCallLog -> "Нет доступа к журналу звонков.\nРазрешите «Журнал звонков» в настройках приложения."
                !showRecents && !book.canReadContacts -> "Нет доступа к контактам."
                entries.isEmpty() && q.isNotEmpty() -> "Ничего не найдено"
                entries.isEmpty() -> if (showRecents) "Звонков пока нет" else "Контактов нет"
                else -> null
            }
            binding.tvListEmpty.text = empty.orEmpty()
            binding.tvListEmpty.visibility = if (empty == null) View.GONE else View.VISIBLE
        }
    }

    private fun matches(q: String, name: String?, number: String): Boolean {
        if (q.isEmpty()) return true
        if (name?.contains(q, ignoreCase = true) == true) return true
        val qd = q.filter { it.isDigit() }
        return qd.isNotEmpty() && qd.length == q.replace(" ", "").replace("+", "").length &&
            (number.filter { it.isDigit() }.contains(qd) || fi.callshift.app.domain.T9.score(qd, name, number) > 0)
    }

    private fun recentEntry(r: PhoneBook.Recent): DialEntry {
        val (label, color) = when (r.type) {
            CallLog.Calls.INCOMING_TYPE -> "↙ Входящий" to 0xFF7FE0AE.toInt()
            CallLog.Calls.OUTGOING_TYPE -> "↗ Исходящий" to 0xFF90CAF9.toInt()
            CallLog.Calls.MISSED_TYPE -> "↙ Пропущенный" to 0xFFFF8A80.toInt()
            CallLog.Calls.REJECTED_TYPE -> "⊘ Отклонён" to 0xFFFFB74D.toInt()
            CallLog.Calls.BLOCKED_TYPE -> "⊘ Заблокирован" to 0xFFFFB74D.toInt()
            CallLog.Calls.VOICEMAIL_TYPE -> "✉ Голосовая почта" to 0xFFBFD6C9.toInt()
            else -> "Звонок" to 0xFFBFD6C9.toInt()
        }
        val count = if (r.count > 1) " (${r.count})" else ""
        val time = DateUtils.getRelativeTimeSpanString(r.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE)
        val dur = if (r.durationSec > 0) " · " + DateUtils.formatElapsedTime(r.durationSec) else ""
        val sim = simLabel(r.accountId)?.let { " · $it" }.orEmpty()
        val title = (r.name ?: r.number.ifBlank { "Скрытый номер" }) + count
        return DialEntry(title, "$label · $time$dur$sim", r.number, r.name, color)
    }

    private fun simLabel(accountId: String?): String? {
        if (accountId == null || simByButton.size < 2) return null
        val idx = simByButton.values.indexOf(accountId)
        return if (idx >= 0) "SIM ${idx + 1}" else null
    }

    private fun showEntryMenu(e: DialEntry) {
        if (e.number.isBlank()) return
        val actions = listOf(
            "Позвонить" to { placeCall(e.number) },
            "Написать SMS" to { runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + e.number))) } },
            "Изменить перед вызовом" to {
                binding.tabs.check(binding.tabPad.id)
                digits.clear(); digits.append(e.number); updateDisplay()
            },
            "Скопировать номер" to {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("number", e.number))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            },
            "В белый список" to {
                val n = app.normalizer.normalize(e.number).e164 ?: e.number
                app.settings.setWhitelist(app.settings.whitelist + n)
                Toast.makeText(this, "Добавлено в белый список", Toast.LENGTH_SHORT).show()
            },
        )
        AlertDialog.Builder(this)
            .setTitle(e.name ?: e.number)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun updateT9(text: String) {
        t9Job?.cancel()
        if (text.isEmpty() || text.any { it == '*' || it == '#' }) { t9Adapter.items = emptyList(); return }
        t9Job = lifecycleScope.launch {
            delay(80)
            t9Adapter.items = book.t9(text).map {
                DialEntry(it.name, "${it.label} · ${it.number}", it.number, it.name.takeIf { n -> n != it.number })
            }
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
        updateT9(text)
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
        const val EXTRA_AUTODIAL = "autodial"
        const val EXTRA_SHOW_RECENTS = "show_recents"
    }
}
