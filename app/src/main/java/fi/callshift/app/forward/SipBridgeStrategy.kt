package fi.callshift.app.forward

import fi.callshift.app.domain.ForwardResult
import fi.callshift.app.domain.PermissionProfile
import fi.callshift.app.domain.StrategyId

/**
 * S3 — SIP/VoIP-мост (ТЗ п. 4.5, milestone M3).
 *
 * В сборках M1/M2 стратегия зарегистрирована, но честно сообщает
 * о недоступности: SIP-стек (PJSIP/linphone-sdk) и self-managed
 * ConnectionService подключаются отдельным этапом.
 *
 * Требование ТЗ FR-A1 / R-5: приложение обязано явно показывать, какой уровень
 * аудио-моста возможен на данном устройстве:
 *   L0 — сток, только «слепая» переадресация в SIP (без связывания аудио);
 *   L1 — сток + self-managed connection, частичный мост;
 *   L2 — root, REMOTE_SUBMIX/VOICE_CALL, полный мост;
 *   L3 — system-подпись, доступ к modem-audio.
 */
class SipBridgeStrategy(
    private val audioBridgeLevel: () -> Int = { 0 },
) : ForwardStrategy {

    override val id = StrategyId.SIP_BRIDGE

    override fun isAvailable(profile: PermissionProfile): Boolean = false // M3

    override suspend fun execute(req: ForwardRequest): ForwardResult {
        lastReason = "SIP-модуль (M3) не подключён; текущий уровень аудио-моста L${audioBridgeLevel()}"
        return ForwardResult.NotAvailable
    }

    var lastReason: String = ""
        private set

    companion object {
        /** Детект уровня аудио-моста: root/privilege-проверки (ТЗ п. 10.7). */
        fun detectAudioBridgeLevel(rootAvailable: Boolean, privilegedAudioGranted: Boolean): Int = when {
            privilegedAudioGranted -> 3
            rootAvailable -> 2
            else -> 0
        }
    }
}
