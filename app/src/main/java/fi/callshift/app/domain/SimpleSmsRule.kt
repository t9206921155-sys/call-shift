package fi.callshift.app.domain

object SimpleSmsRule {
    fun supports(rule: Rule): Boolean = rule.action.strategy == StrategySpec.NONE &&
        rule.action.verdict == VerdictSpec.DISALLOW_REJECT && rule.action.target == null &&
        !rule.action.dtmfTransferOriginal && rule.action.endpoint == null &&
        ReplyOptions.channels(rule.action.replyChannel, rule.action.replyChannels) == listOf(ReplyChannel.SMS) &&
        rule.conditions.anyOf.size <= 1 && rule.conditions.anyOf.flatten().let { conditions ->
            conditions.all { it.type in listOf(RuleEngine.TYPE_NUMBER_MATCH, RuleEngine.TYPE_IN_CONTACTS) } &&
                conditions.groupBy { it.type }.values.all { it.size == 1 }
        }
}
