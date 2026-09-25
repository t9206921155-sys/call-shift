package fi.callshift.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import fi.callshift.app.databinding.ActivityRuleEditBinding
import fi.callshift.app.domain.ScheduleSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Блок «Когда действует» в редакторе правила: дни, время, срок действия. */
class ScheduleEditor(private val ctx: Context, private val b: ActivityRuleEditBinding) {

    private var from: String? = null
    private var to: String? = null
    private var dateFrom: LocalDate? = null
    private var dateTo: LocalDate? = null
    private val dayChips get() = listOf(b.chipDay1, b.chipDay2, b.chipDay3, b.chipDay4, b.chipDay5, b.chipDay6, b.chipDay7)
    private val zone get() = ZoneId.systemDefault()
    private val dfmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun setup() {
        dayChips.forEach { c -> c.setOnCheckedChangeListener { _, _ -> refresh() } }
        b.presetAlways.setOnClickListener { apply(emptyList(), null, null) }
        b.presetWork.setOnClickListener { apply(listOf(1, 2, 3, 4, 5), "09:00", "18:00") }
        b.presetNight.setOnClickListener { apply(emptyList(), "23:00", "07:00") }
        b.presetWeekend.setOnClickListener { apply(listOf(6, 7), null, null) }
        b.btnTimeFrom.setOnClickListener { pickTime(from ?: "09:00") { from = it; if (to == null) to = "18:00"; refresh() } }
        b.btnTimeTo.setOnClickListener { pickTime(to ?: "18:00") { to = it; if (from == null) from = "09:00"; refresh() } }
        b.btnTimeClear.setOnClickListener { from = null; to = null; refresh() }
        b.btnDateFrom.setOnClickListener { pickDate(dateFrom) { dateFrom = it; refresh() } }
        b.btnDateTo.setOnClickListener { pickDate(dateTo ?: dateFrom) { dateTo = it; refresh() } }
        b.btnDateClear.setOnClickListener { dateFrom = null; dateTo = null; refresh() }
        refresh()
    }

    fun load(s: ScheduleSpec?, validFrom: Long?, validTo: Long?) {
        apply(s?.days ?: emptyList(), s?.from, s?.to, keepDates = true)
        dateFrom = validFrom?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        dateTo = validTo?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        refresh()
    }

    private fun apply(days: List<Int>, f: String?, t: String?, keepDates: Boolean = true) {
        dayChips.forEachIndexed { i, c -> c.isChecked = (i + 1) in days }
        from = f; to = t
        refresh()
    }

    fun schedule(): ScheduleSpec? {
        val days = dayChips.mapIndexedNotNull { i, c -> if (c.isChecked) i + 1 else null }
        val d = if (days.size == 7) emptyList() else days
        if (d.isEmpty() && (from == null || to == null)) return null
        return ScheduleSpec(days = d, from = from?.takeIf { to != null }, to = to?.takeIf { from != null })
    }

    fun validFrom(): Long? = dateFrom?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
    fun validTo(): Long? = dateTo?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()?.minus(1)

    /** null — всё корректно, иначе текст ошибки. */
    fun error(): String? =
        if (dateFrom != null && dateTo != null && dateTo!!.isBefore(dateFrom)) "Дата «по» раньше даты «с»" else null

    private fun refresh() {
        b.btnTimeFrom.text = "с ${from ?: "—:—"}"
        b.btnTimeTo.text = "до ${to ?: "—:—"}"
        b.btnDateFrom.text = dateFrom?.let { "с ${dfmt.format(it)}" } ?: "с даты"
        b.btnDateTo.text = dateTo?.let { "по ${dfmt.format(it)}" } ?: "по дату"
        b.tvScheduleSummary.text = describe(schedule(), dateFrom, dateTo)
    }

    private fun describe(s: ScheduleSpec?, df: LocalDate?, dt: LocalDate?): String {
        val parts = mutableListOf<String>()
        if (s == null) parts += "Всегда" else {
            parts += when (s.days.sorted()) {
                emptyList<Int>() -> "Каждый день"
                listOf(1, 2, 3, 4, 5) -> "По будням"
                listOf(6, 7) -> "По выходным"
                else -> s.days.sorted().joinToString(", ") { DAY_NAMES[it - 1] }
            }
            if (s.from != null && s.to != null) {
                parts += "с ${s.from} до ${s.to}" + if (s.to!! <= s.from!!) " (через полночь)" else ""
            }
        }
        if (df != null || dt != null) {
            parts += buildString {
                append("действует")
                df?.let { append(" с ${dfmt.format(it)}") }
                dt?.let { append(" по ${dfmt.format(it)}") }
            }
            val today = LocalDate.now(zone)
            if (dt != null && dt.isBefore(today)) parts += "⚠ срок уже истёк"
        }
        return parts.joinToString(" · ")
    }

    private fun pickTime(cur: String, onPick: (String) -> Unit) {
        val (h, m) = cur.split(":").map { it.toInt() }
        TimePickerDialog(ctx, { _, hh, mm -> onPick("%02d:%02d".format(hh, mm)) }, h, m, true).show()
    }

    private fun pickDate(cur: LocalDate?, onPick: (LocalDate) -> Unit) {
        val d = cur ?: LocalDate.now(zone)
        DatePickerDialog(ctx, { _, y, mo, dd -> onPick(LocalDate.of(y, mo + 1, dd)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    companion object {
        /** Короткое описание расписания/срока для карточки правила, null если «всегда». */
        fun shortText(r: fi.callshift.app.domain.Rule): String? {
            val parts = mutableListOf<String>()
            r.schedule?.let { s ->
                when (s.days.sorted()) {
                    emptyList<Int>() -> {}
                    listOf(1, 2, 3, 4, 5) -> parts += "будни"
                    listOf(6, 7) -> parts += "выходные"
                    else -> parts += s.days.sorted().joinToString(",") { DAY_NAMES[it - 1] }
                }
                if (s.from != null && s.to != null) parts += "${s.from}–${s.to}"
            }
            val f = DateTimeFormatter.ofPattern("dd.MM")
            val z = ZoneId.systemDefault()
            r.validTo?.let { parts += "до " + f.format(Instant.ofEpochMilli(it).atZone(z)) }
            if (r.validTo == null) r.validFrom?.let { parts += "с " + f.format(Instant.ofEpochMilli(it).atZone(z)) }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

        val DAY_NAMES = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
    }
}
