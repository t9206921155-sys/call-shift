package fi.callshift.app.telegram

import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.*
import fi.callshift.app.forward.CallEvent
import io.github.up9cloud.td.JsonClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One TDLib client, one receive loop, sequential update processing. Never logs TDLib payloads. */
class TelegramAccountClient(private val app: CallShiftApp) {
    val vault = TelegramVault(app)
    private val scope = app.appScope
    private val auth = MutableStateFlow("notStarted")
    val authorization: StateFlow<String> = auth
    private val notice = MutableStateFlow("")
    val status: StateFlow<String> = notice
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private data class Pending(val response: CompletableDeferred<JSONObject>, val event: CallEvent? = null)
    private val requests = ConcurrentHashMap<String, Pending>()
    @Volatile private var clientId = 0
    @Volatile private var sessionEpoch = 0L
    private var parameterClient = 0
    private val parametersLock = kotlinx.coroutines.sync.Mutex()
    private var receiving = false
    private val updates = Channel<JSONObject>(Channel.UNLIMITED)

    fun configured(): Boolean = runCatching { !vault.read("api_id").isNullOrBlank() && !vault.read("api_hash").isNullOrBlank() }.getOrDefault(false)
    fun autoEnabled(): Boolean = runCatching { vault.read("auto_enabled") == "true" }.getOrDefault(false)
    fun enableAuto(enabled: Boolean) = vault.write("auto_enabled", enabled.toString())

    suspend fun configure(apiId: String, apiHash: String) {
        require(apiId.toIntOrNull()?.let { it > 0 } == true && apiHash.matches(Regex("[a-fA-F0-9]{32}"))) { "Проверьте API ID и API hash с my.telegram.org" }
        check(auth.value in listOf("notStarted", "authorizationStateWaitTdlibParameters", "authorizationStateClosed")) { "Сначала выйдите из аккаунта" }
        vault.write("api_id", apiId); vault.write("api_hash", apiHash)
        start()
        if (auth.value == "authorizationStateWaitTdlibParameters") parameters()
    }

    @Synchronized fun start() {
        check(configured()) { "Сначала настройте API ID/API hash в Telegram → Подключить" }
        if (!receiving) {
            try {
                JsonClient.load()
                JsonClient.td_execute(obj("setLogStream").put("log_stream", obj("logStreamEmpty")).toString())
                JsonClient.td_execute(obj("setLogVerbosityLevel").put("new_verbosity_level", 0).toString())
            } catch (_: LinkageError) { throw IllegalStateException("TDLib недоступна для архитектуры этого устройства") }
            receiving = true
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val raw = JsonClient.td_receive(1.0) ?: continue
                    runCatching { JSONObject(raw) }.getOrNull()?.let { updates.send(it) }
                }
            }
            scope.launch(Dispatchers.IO) {
                for (update in updates) {
                    try { route(update) } catch (_: Exception) { notice.value = "Ошибка обработки Telegram. Проверьте статус и журнал." }
                }
            }
        }
        if (clientId == 0) {
            clientId = JsonClient.td_create_client_id()
            JsonClient.td_send(clientId, obj("getAuthorizationState").toString())
        }
    }

    private suspend fun parameters() = parametersLock.withLock {
        if (parameterClient == clientId || auth.value != "authorizationStateWaitTdlibParameters") return@withLock
        parameterClient = clientId
        try {
        val root = java.io.File(app.noBackupFilesDir, "telegram").apply { mkdirs() }
        request(obj("setTdlibParameters")
            .put("database_directory", java.io.File(root, "db").absolutePath)
            .put("files_directory", java.io.File(root, "files").absolutePath)
            .put("database_encryption_key", vault.databaseKey())
            .put("use_test_dc", false).put("use_file_database", false)
            .put("use_chat_info_database", true).put("use_message_database", true).put("use_secret_chats", false)
            .put("api_id", vault.read("api_id")!!.toInt()).put("api_hash", vault.read("api_hash"))
            .put("system_language_code", "ru").put("device_model", "CallShift Android")
            .put("system_version", android.os.Build.VERSION.RELEASE).put("application_version", fi.callshift.app.BuildConfig.VERSION_NAME))
        } catch (error: Exception) { parameterClient = 0; throw error }
    }

    suspend fun authenticate(value: String) {
        start()
        val command = when (auth.value) {
            "authorizationStateWaitPhoneNumber" -> obj("setAuthenticationPhoneNumber").put("phone_number", value)
            "authorizationStateWaitCode" -> obj("checkAuthenticationCode").put("code", value)
            "authorizationStateWaitPassword" -> obj("checkAuthenticationPassword").put("password", value)
            "authorizationStateWaitEmailAddress" -> obj("setAuthenticationEmailAddress").put("email_address", value)
            "authorizationStateWaitEmailCode" -> obj("checkAuthenticationEmailCode").put("code", obj("emailAddressAuthenticationCode").put("code", value))
            else -> throw IllegalStateException("Этот этап входа здесь не поддерживается. Регистрация, QR и платный вход автоматически не выполняются.")
        }
        request(command)
    }

    suspend fun logout() {
        enableAuto(false)
        sessionEpoch++
        if (clientId != 0) request(obj("logOut"))
        // TDLib removes the authorization key on logout. Keep API configuration
        // and DB encryption key until TDLib has finished closing its database.
    }

    private suspend fun request(command: JSONObject, event: CallEvent? = null): JSONObject {
        val id = UUID.randomUUID().toString()
        val response = CompletableDeferred<JSONObject>()
        requests[id] = Pending(response, event)
        try {
            JsonClient.td_send(clientId, command.put("@extra", id).toString())
            return withTimeout(25_000) { response.await() }.also {
                if (it.optString("@type") == "error") throw TelegramException(it.optInt("code"))
            }
        } finally { requests.remove(id) }
    }

    private suspend fun route(update: JSONObject) {
        val type = update.optString("@type")
        if (type == "updateAuthorizationState" || type.startsWith("authorizationState")) {
            val state = if (type == "updateAuthorizationState") update.getJSONObject("authorization_state") else update
            val changed = auth.value != state.getString("@type")
            auth.value = state.getString("@type")
            notice.value = ""
            if (changed) sessionEpoch++
            if (changed) when (auth.value) {
                "authorizationStateWaitTdlibParameters" -> scope.launch {
                    runCatching { parameters() }.onFailure { notice.value = "Не удалось настроить TDLib. Проверьте API ID/API hash." }
                }
                "authorizationStateClosed" -> {
                    clientId = 0; parameterClient = 0; enableAuto(false)
                    for (key in vault.names("pending:")) {
                        val event = vault.read(key)?.let { json.decodeFromString<CallEvent>(it) }
                        if (event != null) publish(event, "TG_UNKNOWN", "Сессия закрыта до подтверждения отправки. Автоповтор не выполняется.")
                        vault.remove(key)
                    }
                }
                "authorizationStateReady" -> notice.value = "Аккаунт подключён. Это не гарантирует поиск любого номера."
            }
        }
        val extra = update.optString("@extra")
        requests[extra]?.let { pending ->
            pending.event?.let { event ->
                if (type == "message") {
                    val messageId = update.getLong("id")
                    val chatId = update.getLong("chat_id")
                    val sendingState = update.optJSONObject("sending_state")?.optString("@type")
                    if (TelegramReplyPolicy.initialStatus(sendingState) == "TG_SENT") publish(event, "TG_SENT", "Telegram подтвердил отправку. Прочтение и доставка на устройство не подтверждены.")
                    else if (TelegramReplyPolicy.initialStatus(sendingState) == "FAILED") {
                        publish(event, "FAILED", "Telegram отклонил отправку. Автоповтора нет.")
                    } else {
                        // Bound private metadata; no raw message text or auth secrets.
                        val keys = vault.names("pending:")
                        if (keys.size >= 500) vault.remove(keys.first())
                        vault.write("pending:$chatId:$messageId", json.encodeToString(event))
                        publish(event, "TG_PENDING", "Telegram принял сообщение в очередь. Подтверждения отправки ещё нет; автоповтора не будет.")
                    }
                }
            }
            pending.response.complete(update)
        }
        if (type == "updateMessageSendSucceeded" || type == "updateMessageSendFailed") {
            val message = update.getJSONObject("message")
            val key = "pending:${message.getLong("chat_id")}:${update.getLong("old_message_id")}"
            val serialized = vault.read(key) ?: return
            val event = json.decodeFromString<CallEvent>(serialized)
            if (type == "updateMessageSendSucceeded") publish(event, "TG_SENT", "Telegram подтвердил отправку. Прочтение и доставка на устройство не подтверждены.")
            else publish(event, "FAILED", TelegramReplyPolicy.error(update.optJSONObject("error")?.optInt("code") ?: 0))
            vault.remove(key)
        }
    }

    /** No importContacts, guessed usernames, UI clicks, SMS fallback or automatic retries. */
    suspend fun reply(ctx: CallContext, decision: Decision, text: String) {
        val event = CallEvent(System.currentTimeMillis(), ctx.direction.name, ctx.e164,
            app.normalizer.mask(ctx.e164), ctx.phoneAccount?.label ?: "—", decision.ruleId, decision.ruleName,
            "TELEGRAM_REPLY", ctx.e164, "TG_LOOKUP", null, null, decision.reason,
            decision.engineMs, 0, decision.engineMs, UUID.randomUUID().toString())
        var submitted = false
        try {
            check(autoEnabled()) { "Автоотправка Telegram выключена. Включите её на экране подключения." }
            val number = ctx.e164 ?: error("Номер скрыт")
            require(ReplyChannel.isPhoneAddress(number)) { "Некорректный номер" }
            start()
            publish(event, "TG_LOOKUP", "Определяем получателя по номеру. Контакты не импортируются.")
            withTimeout(25_000) { auth.first { it == "authorizationStateReady" } }
            val sendingEpoch = sessionEpoch
            val me = request(obj("getMe"))
            val user = request(obj("searchUserByPhoneNumber").put("phone_number", number).put("only_local", false))
            val id = user.getLong("id")
            check(TelegramReplyPolicy.canAddress(number, user.optString("phone_number"),
                user.optJSONObject("type")?.optString("@type") != "userTypeRegular", id == me.getLong("id"))) {
                "Номер получателя не подтверждён Telegram, либо это бот/собственный аккаунт. Отправка запрещена."
            }
            check(autoEnabled()) { "Автоотправка выключена" }
            val chat = request(obj("createPrivateChat").put("user_id", id).put("force", false))
            check(autoEnabled() && sessionEpoch == sendingEpoch && auth.value == "authorizationStateReady") {
                "Сессия Telegram изменилась или автоотправка выключена. Отправка отменена."
            }
            publish(event, "TG_PENDING", "Запрашиваем отправку Telegram. При неизвестном результате повтор не выполняется.")
            submitted = true
            request(obj("sendMessage").put("chat_id", chat.getLong("id"))
                .put("options", obj("messageSendOptions").put("paid_message_star_count", 0))
                .put("input_message_content", obj("inputMessageText")
                    .put("text", obj("formattedText").put("text", text))
                    .put("clear_draft", false)), event)
        } catch (error: TimeoutCancellationException) {
            publish(event, if (submitted) "TG_UNKNOWN" else "FAILED",
                if (submitted) "Нет ответа Telegram. Отправка могла состояться; автоповтор отключён."
                else "Нет связи или аккаунт не авторизован. Откройте настройки Telegram.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            publish(event, "FAILED", when (error) {
                is TelegramException -> TelegramReplyPolicy.error(error.code)
                is IllegalStateException, is IllegalArgumentException -> error.message ?: "Ошибка настройки Telegram"
                else -> "Ошибка Telegram. Проверьте подключение; SMS не отправлялась."
            })
        }
    }

    private suspend fun publish(event: CallEvent, result: String, message: String) {
        app.eventStore.record(event.copy(result = result, errorMessage = message,
            errorCode = if (result == "FAILED" || result == "TG_UNKNOWN") "telegram_$result" else null))
    }
    private class TelegramException(val code: Int) : Exception(TelegramReplyPolicy.error(code))
    private fun obj(type: String) = JSONObject().put("@type", type)
}
