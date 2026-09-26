package fi.callshift.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/** Explicit fan-out, not fallback: failures in one channel do not suppress another. */
object ReplyFanout {
    suspend fun run(channels: List<String>, onFailure: suspend (String, Exception) -> Unit, send: suspend (String) -> Unit) = supervisorScope {
        channels.distinct().map { channel ->
            async {
                try { send(channel) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onFailure(channel, error) }
            }
        }.awaitAll()
        Unit
    }
}
