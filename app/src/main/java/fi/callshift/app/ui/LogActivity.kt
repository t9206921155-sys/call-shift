package fi.callshift.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import fi.callshift.app.databinding.ActivityLogBinding
import fi.callshift.app.databinding.ItemLogEventBinding
import fi.callshift.app.forward.CallEvent
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }
    private val dateFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        binding.chipsFilter.setOnCheckedStateChangeListener { _, _ -> render() }
        binding.etLogSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = render()
        })
        loadEvents()
    }

    private fun setupButtons() {
        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Очистить журнал?")
                .setPositiveButton("Очистить") { _, _ ->
                    lifecycleScope.launch {
                        app.eventStore.clear()
                        loadEvents()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        binding.btnExport.setOnClickListener {
            exportCsv()
        }
    }

    private var all: List<CallEvent> = emptyList()
    private val names = mutableMapOf<String, String?>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("d MMMM, EEEE", Locale("ru"))

    private fun loadEvents() {
        lifecycleScope.launch {
            all = app.eventStore.events(limit = 1000)
            all.mapNotNull { it.numberE164 }.distinct().filter { it !in names }.forEach { n ->
                names[n] = runCatching { app.contacts.contactName(n) }.getOrNull()
            }
            render()
        }
    }

    private fun filterMatches(e: CallEvent): Boolean {
        val k = EventView.kind(e)
        val byChip = when (binding.chipsFilter.checkedChipId) {
            R.id.fRejected -> k == EventView.Kind.REJECTED || k == EventView.Kind.SILENCED
            R.id.fPassed -> k == EventView.Kind.PASSED
            R.id.fSms -> e.strategy == "SMS_REPLY"
            R.id.fForward -> k == EventView.Kind.FORWARDED
            R.id.fErrors -> k == EventView.Kind.ERROR
            else -> true
        }
        if (!byChip) return false
        val q = binding.etLogSearch.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return true
        val qd = q.filter { it.isDigit() }
        return (qd.isNotEmpty() && e.numberE164?.filter { it.isDigit() }?.contains(qd) == true) ||
            names[e.numberE164]?.contains(q, true) == true ||
            e.ruleName?.contains(q, true) == true ||
            e.errorMessage?.contains(q, true) == true
    }

    private fun render() {
        val events = all.filter(::filterMatches)
        binding.eventsContainer.removeAllViews()
        val today = EventView.startOfToday()
        binding.tvLogStats.text = "Сегодня: " + EventView.stats(all.filter { it.ts >= today }).text()
        binding.tvEmpty.text = if (all.isEmpty()) "Журнал пуст" else "Ничего не найдено"
        binding.tvEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        binding.scrollView.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        var lastDay = ""
        events.take(300).forEach { event ->
            val day = dayLabel(event.ts)
            if (day != lastDay) {
                lastDay = day
                binding.eventsContainer.addView(android.widget.TextView(this).apply {
                    text = day
                    textSize = 14f
                    setTextColor(getColor(R.color.brand_accent))
                    setPadding(4, if (binding.eventsContainer.childCount == 0) 4 else 24, 0, 8)
                    paint.isFakeBoldText = true
                })
            }
            val itemBinding = ItemLogEventBinding.inflate(inflater, binding.eventsContainer, false)
            bindEvent(itemBinding, event)
            binding.eventsContainer.addView(itemBinding.root)
        }
    }

    private fun dayLabel(ts: Long): String {
        val today = EventView.startOfToday()
        return when {
            ts >= today -> "Сегодня"
            ts >= today - 86_400_000L -> "Вчера"
            else -> dayFormat.format(Date(ts))
        }
    }

    private fun bindEvent(item: ItemLogEventBinding, event: CallEvent) {
        val kind = EventView.kind(event)
        val name = names[event.numberE164]
        val num = when {
            event.numberE164 == null -> "Скрытый номер"
            app.settings.maskNumbersInUi -> event.numberMasked
            else -> event.numberE164
        }
        item.tvNumber.text = name ?: num
        item.tvResult.text = "${kind.icon} ${kind.title}"
        item.tvResult.setTextColor(kind.color)
        item.vStripe.setBackgroundColor(kind.color)
        item.tvDetail.text = EventView.sentence(event)
        item.tvMeta.text = buildString {
            append(timeFormat.format(Date(event.ts)))
            if (name != null) append(" · ").append(num)
            if (event.sim != "—") append(" · SIM: ").append(event.sim)
        }
        item.root.setOnClickListener { showEventMenu(event, name) }
    }

    private fun showEventMenu(e: CallEvent, name: String?) {
        val number = e.numberE164 ?: return
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "Позвонить" to {
                startActivity(Intent(this, DialerActivity::class.java)
                    .setData(android.net.Uri.fromParts("tel", number, null))
                    .putExtra(DialerActivity.EXTRA_AUTODIAL, true))
            },
            "Написать SMS" to {
                runCatching { startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$number"))) }
                Unit
            },
            "В белый список" to {
                app.settings.setWhitelist(app.settings.whitelist + number)
                Toast.makeText(this, "Добавлено в белый список", Toast.LENGTH_SHORT).show()
            },
        )
        e.ruleId?.let { id ->
            actions += "Открыть правило" to {
                startActivity(Intent(this, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_RULE_ID, id))
            }
        }
        actions += "Подробности" to {
            AlertDialog.Builder(this).setTitle(name ?: number).setMessage(
                "Время: ${dateFormat.format(Date(e.ts))}\nПравило: ${e.ruleName ?: "—"}\n" +
                    "Действие: ${RuleLabels.strategyTitle(e.strategy)}\nРезультат: ${e.result}\n" +
                    "Причина: ${e.reason}\n${e.errorMessage.orEmpty()}\nОбработка: ${e.totalMs} мс",
            ).setPositiveButton("OK", null).show()
            Unit
        }
        AlertDialog.Builder(this)
            .setTitle(name ?: number)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun exportCsv() {
        lifecycleScope.launch {
            val csv = app.eventStore.exportCsv(maskNumbers = app.settings.maskNumbersInLogs)
            val cacheFile = File(cacheDir, "callshift_events.csv")
            cacheFile.writeText(csv)

            val uri = FileProvider.getUriForFile(this@LogActivity, "$packageName.fileprovider", cacheFile)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Экспорт журнала CSV"))
        }
    }
}
