package fi.callshift.app

import android.app.Application
import android.content.Context
import fi.callshift.app.data.AndroidNotifierAdapter
import fi.callshift.app.data.ContactsChecker
import fi.callshift.app.data.FileEventStore
import fi.callshift.app.data.JsonFileRuleStore
import fi.callshift.app.data.SettingsStore
import fi.callshift.app.domain.PermissionProfile
import fi.callshift.app.domain.PhoneNumberNormalizer
import fi.callshift.app.domain.RuleEngine
import fi.callshift.app.domain.StrategyId
import fi.callshift.app.forward.CallbackForwardStrategy
import fi.callshift.app.forward.ForwardDispatcher
import fi.callshift.app.forward.ForwardGuard
import fi.callshift.app.forward.ForwardStrategy
import fi.callshift.app.forward.MmiForwardStrategy
import fi.callshift.app.forward.NotifyForwardStrategy
import fi.callshift.app.forward.SipBridgeStrategy
import fi.callshift.app.telecom.AndroidTelecomPort
import fi.callshift.app.telecom.InCallController
import fi.callshift.app.telecom.PermissionProfileDetector
import fi.callshift.app.util.AndroidNotifier
import fi.callshift.app.util.ExternalRetryQueueImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Класс Application: контейнер зависимостей приложения CallShift.
 */
class CallShiftApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var normalizer: PhoneNumberNormalizer
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var ruleStore: JsonFileRuleStore
        private set
    lateinit var eventStore: FileEventStore
        private set
    lateinit var contacts: ContactsChecker
        private set
    lateinit var telecom: AndroidTelecomPort
        private set
    lateinit var detector: PermissionProfileDetector
        private set
    lateinit var notifier: AndroidNotifier
        private set
    lateinit var notifierAdapter: AndroidNotifierAdapter
        private set
    lateinit var guard: ForwardGuard
        private set
    lateinit var retryQueue: ExternalRetryQueueImpl
        private set
    lateinit var ruleEngine: RuleEngine
        private set
    lateinit var dispatcher: ForwardDispatcher
        private set
    lateinit var smsReplier: fi.callshift.app.sms.SmsAutoReplier
        private set

    val profile: PermissionProfile
        get() = detector.detect().profile

    override fun onCreate() {
        super.onCreate()
        fi.callshift.app.util.CrashReporter.install(this)

        normalizer = PhoneNumberNormalizer(defaultRegion = "FI")
        settings = SettingsStore(this)
        ruleStore = JsonFileRuleStore(this)
        eventStore = FileEventStore(this)
        contacts = ContactsChecker(this, normalizer)
        telecom = AndroidTelecomPort(this)
        detector = PermissionProfileDetector(this)
        notifier = AndroidNotifier(this)
        notifierAdapter = AndroidNotifierAdapter(this, notifier)
        guard = ForwardGuard(settings, normalizer)
        retryQueue = ExternalRetryQueueImpl(this)

        val inCallController = InCallController.get()

        val strategies: Map<StrategyId, ForwardStrategy> = mapOf(
            StrategyId.MMI_FORWARD to MmiForwardStrategy(telecom, normalizer),
            StrategyId.CALLBACK_DIAL to CallbackForwardStrategy(
                telecom = telecom,
                guard = guard,
                normalizer = normalizer,
                dtmf = { target, original, ruleName ->
                    inCallController.pendingDtmf = InCallController.PendingDtmf(
                        targetNumber = target,
                        originalNumber = original,
                        ruleName = ruleName,
                    )
                },
            ),
            StrategyId.NOTIFY to NotifyForwardStrategy(
                notifier = notifierAdapter,
                retryQueue = retryQueue,
            ),
            StrategyId.SIP_BRIDGE to SipBridgeStrategy {
                SipBridgeStrategy.detectAudioBridgeLevel(
                    rootAvailable = detector.hasRoot(),
                    privilegedAudioGranted = false,
                )
            },
        )

        dispatcher = ForwardDispatcher(
            strategies = strategies,
            profileProvider = { profile },
            recorder = eventStore,
            normalizer = normalizer,
            scope = appScope,
        )

        smsReplier = fi.callshift.app.sms.SmsAutoReplier(this, eventStore, normalizer)

        ruleEngine = RuleEngine(
            ruleStore = ruleStore,
            contacts = contacts,
            settings = settings,
            profileProvider = { profile },
            simIndexProvider = { ref -> telecom.simIndex(ref?.id) },
        )
    }

    companion object {
        fun from(context: Context): CallShiftApp =
            context.applicationContext as CallShiftApp
    }
}
