package fi.callshift.app.ui

import fi.callshift.app.domain.StrategySpec
import fi.callshift.app.domain.VerdictSpec

/** Русские названия и пояснения для стратегий и вердиктов (значения в JSON остаются английскими). */
object RuleLabels {

    data class Label(val title: String, val hint: String, val needsTarget: Boolean = false)

    val strategies: Map<StrategySpec, Label> = linkedMapOf(
        StrategySpec.NONE to Label(
            "Ничего не делать",
            "Никуда не переадресовывать. Со звонком происходит только то, что выбрано в поле " +
                "«Что сделать со звонком» ниже. Выберите это, чтобы просто сбросить или заглушить звонок.",
        ),
        StrategySpec.CALLBACK_DIAL to Label(
            "Сбросить и перезвонить на другой номер",
            "Звонок сбрасывается, а телефон сам звонит на указанный номер (например, коллеге или на " +
                "второй телефон). Звонящий НЕ соединяется с ним — это новый звонок с вашей SIM.",
            needsTarget = true,
        ),
        StrategySpec.MMI_FORWARD to Label(
            "Переадресация через оператора",
            "Включает у оператора переадресацию (код *21*номер#) на указанный номер. Звонящий " +
                "попадает прямо на этот номер. Действует на ВСЕ следующие звонки, пока не отключите " +
                "(кнопка «Паника» или код ##002#). Оператор может брать плату.",
            needsTarget = true,
        ),
        StrategySpec.NOTIFY to Label(
            "Только уведомить",
            "Показывает уведомление о звонке с названием правила. Звонок обрабатывается так, " +
                "как выбрано ниже (обычно — пропустить).",
        ),
        StrategySpec.SIP_BRIDGE to Label(
            "SIP-мост (пока не работает)",
            "Соединение через интернет-телефонию. В этой версии не реализовано — не выбирайте.",
            needsTarget = true,
        ),
    )

    val verdicts: Map<VerdictSpec, Label> = linkedMapOf(
        VerdictSpec.DISALLOW_REJECT to Label(
            "Сбросить",
            "Звонок сразу отклоняется, как будто вы нажали «Отклонить». Звонящий слышит «занято» " +
                "или попадает на автоответчик оператора.",
        ),
        VerdictSpec.DISALLOW_AS_MISSED to Label(
            "Сбросить и записать в пропущенные",
            "Звонок отклоняется, но в журнале остаётся как пропущенный. На части телефонов " +
                "работает как обычный сброс.",
        ),
        VerdictSpec.SILENCE to Label(
            "Без звука",
            "Телефон не звонит и не вибрирует, но звонок виден на экране — можно ответить самому.",
        ),
        VerdictSpec.PASS to Label(
            "Пропустить (звонит как обычно)",
            "Звонок проходит как обычно. Подходит для «Только уведомить».",
        ),
    )

    fun strategyTitle(name: String): String =
        runCatching { strategies[StrategySpec.valueOf(name)]?.title }.getOrNull()
            ?: when (name) {
                "PASS" -> "Без переадресации"
                "TELEGRAM_REPLY" -> "Автоответ Telegram (мой аккаунт)"
                "SMS_REPLY" -> "SMS-автоответ"
                "MESSENGER_DRAFT" -> "Ответ через мессенджер (вручную)"
                "SCREENED" -> "Звонок перехвачен"
                else -> name
            }

    fun verdictTitle(v: VerdictSpec): String = verdicts[v]?.title ?: v.name
}
