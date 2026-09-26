package fi.callshift.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import fi.callshift.app.data.SettingsBackupManager
import fi.callshift.app.domain.BackupCodec
import fi.callshift.app.sms.SmsBudgetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmsSafetyActivity : AppCompatActivity() {
    private val app by lazy { CallShiftApp.from(this) }
    private val backup by lazy { SettingsBackupManager(app) }
    private lateinit var summary: TextView
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val content = BackupCodec.encode(backup.snapshot())
                withContext(Dispatchers.IO) { requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(content) } }
                toast("Копия сохранена. Храните файл в надёжном месте.")
            } catch (_: Exception) { toast("Не удалось сохранить копию. Файл может быть неполным — не используйте его.") }
        }
    }
    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val bytes = ByteArray(BackupCodec.MAX_BYTES + 1)
                    var size = 0
                    requireNotNull(contentResolver.openInputStream(uri)).use { stream ->
                        while (size < bytes.size) {
                            val count = stream.read(bytes, size, bytes.size - size)
                            if (count < 0) break
                            if (count == 0) continue
                            size += count
                        }
                    }
                    require(size <= BackupCodec.MAX_BYTES) { "Копия больше 1 МБ" }
                    BackupCodec.decode(String(bytes, 0, size, Charsets.UTF_8))
                }
                AlertDialog.Builder(this@SmsSafetyActivity)
                    .setTitle("Заменить настройки из копии?")
                    .setMessage("Будут заменены правила (${parsed.rules.size}), автоответчик, белый список и SMS-настройки. Сначала сохраните текущую копию.\n\nВсе импортированные правила, автоответчик и главный переключатель останутся ВЫКЛЮЧЕННЫМИ. Проверьте SIM перед включением. Счётчик расходов не сбрасывается. Уже начатые отправки не отменяются.")
                    .setPositiveButton("Заменить и выключить") { _, _ -> lifecycleScope.launch {
                        try { backup.restore(parsed); toast("Импорт завершён. Проверьте SIM и включите только нужные правила."); refresh() }
                        catch (_: Exception) { toast("Импорт не завершён. Проверьте настройки: защита могла остаться выключенной.") }
                    } }.setNegativeButton("Отмена", null).show()
            } catch (_: Exception) { toast("Копия не принята: неверный формат, версия, параметры или файл больше 1 МБ. Настройки не изменены.") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = FormUi(this)
        ui.title("SMS: защита и копия")
        summary = ui.add(TextView(this).apply { setTextColor(getColor(R.color.text_primary)); textSize = 16f })
        ui.header("Расходы")
        ui.hint("Лимит считает SMS-ЧАСТИ за скользящие 24 часа на всех SIM. Длинное сообщение может стоить как несколько SMS. В лимит входят автоответы, быстрые и тестовые SMS. Неизвестные/неудачные попытки не возвращают резерв — чтобы не создавать платные дубли.")
        val limit = ui.add(EditText(this).apply {
            hint = "Лимит: 1–1000 частей за 24 часа"; inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(getColor(R.color.text_primary)); setText(app.settings.smsDailyLimit.toString())
        })
        val perSim = ui.add(SwitchMaterial(this).apply {
            text = "Отдельная пауза ответа для каждой SIM"; setTextColor(getColor(R.color.text_primary))
            isChecked = app.settings.smsCooldownPerSim
        })
        ui.hint("Выключено — общий интервал для номера на обеих SIM. Включено — личная и рабочая SIM не блокируют ответы друг друга. Интервал выбирается в правиле. Изменение режима не отправляет SMS и не очищает историю; предыдущая общая пауза учитывается до её истечения.")
        ui.add(MaterialButton(this).apply {
            text = "Сохранить защиту SMS"
            setOnClickListener {
                val value = limit.text.toString().toIntOrNull()
                if (value == null || value !in 1..1000) { toast("Введите лимит от 1 до 1000"); return@setOnClickListener }
                fun save() { runCatching { app.settings.setSmsSafety(value, perSim.isChecked) }
                    .onSuccess { toast("Настройки сохранены. История расходов не сброшена."); refresh() }
                    .onFailure { toast("Не удалось сохранить") } }
                if (value > app.settings.smsDailyLimit) AlertDialog.Builder(this@SmsSafetyActivity)
                    .setMessage("Разрешить до $value SMS-частей за 24 часа? Это может увеличить расходы по тарифу.")
                    .setPositiveButton("Увеличить") { _, _ -> save() }.setNegativeButton("Отмена", null).show()
                else save()
            }
        })
        ui.header("Приоритет обработки")
        ui.hint("Отдельный автоответчик проверяется раньше правил. Чтобы работало только ваше SMS-правило, выключите автоответчик.")
        ui.add(MaterialButton(this).apply {
            text = "Выключить отдельный автоответчик"
            setOnClickListener { app.settings.setAutoReplyEnabled(false); refresh(); toast("Автоответчик выключен. Правила не изменены.") }
        })
        ui.add(MaterialButton(this).apply {
            text = "Проверить готовность / тест SMS"
            setOnClickListener { startActivity(Intent(this@SmsSafetyActivity, DiagnosticsActivity::class.java)) }
        })
        ui.header("Резервная копия перед обновлением")
        ui.hint("JSON содержит номера, тексты SMS и правила в открытом виде. Сессии Telegram, ключи, журнал, счётчики расходов и внешние endpoint не экспортируются. При импорте действия выключены. На другом устройстве SIM нужно выбрать заново. Роли Android и разрешения в копию не входят.")
        ui.add(MaterialButton(this).apply {
            text = "Экспорт правил и SMS-настроек"
            setOnClickListener { export.launch("CallShift-settings-${java.time.LocalDate.now()}.json") }
        })
        ui.add(MaterialButton(this).apply {
            text = "Импорт настроек (с подтверждением)"
            setOnClickListener { importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        })
        ui.header("Обновление APK")
        ui.hint("Версия: ${fi.callshift.app.BuildConfig.VERSION_NAME}\nПакет: $packageName\n${if (fi.callshift.app.BuildConfig.STABLE_SIGNING) "Использован настроенный ключ подписи." else "Тестовая подпись. Её постоянство между сборками не гарантировано."}\nДля обновления без удаления нужны тот же пакет и сертификат. При конфликте подписи сначала экспортируйте настройки, не удаляйте приложение наугад.")
        val signature = runCatching {
            packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo!!.apkContentsSigners.first().toByteArray().let {
                    java.security.MessageDigest.getInstance("SHA-256").digest(it).joinToString(":") { b -> "%02X".format(b) }
                }
        }.getOrDefault("не определён")
        ui.hint("Сертификат SHA-256:\n$signature")
        setContentView(ui.scroll)
    }
    override fun onResume() { super.onResume(); if (::summary.isInitialized) refresh() }
    private fun refresh() = lifecycleScope.launch {
        val used = runCatching { SmsBudgetStore.used(this@SmsSafetyActivity) }.getOrNull()
        summary.text = "Резерв SMS за 24 ч: ${used ?: "счётчик недоступен"} / ${app.settings.smsDailyLimit} частей\n" +
            if (app.settings.autoReply.isActiveAt(System.currentTimeMillis())) "ВНИМАНИЕ: автоответчик активен и имеет приоритет над правилами." else "Отдельный автоответчик не активен — используются правила."
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
