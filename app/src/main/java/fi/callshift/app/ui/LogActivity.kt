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

    private fun loadEvents() {
        lifecycleScope.launch {
            val events = app.eventStore.events(limit = 100)
            binding.eventsContainer.removeAllViews()

            if (events.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                binding.tvEmpty.visibility = View.GONE
                val inflater = LayoutInflater.from(this@LogActivity)
                events.forEach { event ->
                    val itemBinding = ItemLogEventBinding.inflate(inflater, binding.eventsContainer, false)
                    bindEvent(itemBinding, event)
                    binding.eventsContainer.addView(itemBinding.root)
                }
            }
        }
    }

    private fun bindEvent(item: ItemLogEventBinding, event: CallEvent) {
        val num = if (app.settings.maskNumbersInUi) event.numberMasked else (event.numberE164 ?: "Аноним")
        item.tvNumber.text = num

        item.tvResult.text = event.result
        item.tvResult.setTextColor(
            if (event.result == "OK" || event.result == "PASS") getColor(R.color.status_ok) else getColor(R.color.status_error),
        )

        val detail = buildString {
            append(RuleLabels.strategyTitle(event.strategy))
            if (!event.target.isNullOrBlank()) {
                val target = if (app.settings.maskNumbersInUi) app.normalizer.mask(event.target) else event.target
                append(" → ").append(target)
            }
            if (event.errorMessage != null) append(" (").append(event.errorMessage).append(")")
        }
        item.tvDetail.text = detail

        val dateStr = dateFormat.format(Date(event.ts))
        item.tvMeta.text = "$dateStr · SIM: ${event.sim} · Правило: ${event.ruleName ?: "—"} · Время: ${event.totalMs}мс"
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
