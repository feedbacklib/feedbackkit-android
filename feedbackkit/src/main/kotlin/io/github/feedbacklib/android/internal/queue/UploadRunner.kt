package io.github.feedbacklib.android.internal.queue

import io.github.feedbacklib.android.ReportSender
import io.github.feedbacklib.android.SendResult
import io.github.feedbacklib.android.internal.core.SdkLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One pass over the queue, oldest report first (spec §9). */
internal class UploadRunner(
    private val store: ReportStore,
    private val sender: ReportSender,
    private val logger: SdkLogger,
) {

    enum class Outcome { DONE, RETRY }

    suspend fun run(): Outcome {
        for (report in store.pending()) {
            val prepared = try {
                report.toPrepared()
            } catch (e: Exception) {
                logger.w("Report ${report.id} is unreadable; set aside as failed", e)
                store.markFailed(report.id)
                continue
            }
            val result = try {
                sender.send(prepared)
            } catch (e: CancellationException) {       // kotlinx.coroutines.CancellationException
                currentCoroutineContext().ensureActive()  // the worker itself was cancelled → propagate
                SendResult.RetryableFailure(e)
            } catch (e: Exception) {
                SendResult.RetryableFailure(e)
            }
            when (result) {
                SendResult.Success -> {
                    store.delete(report.id)
                    logger.d("Report ${report.id} delivered")
                }
                is SendResult.PermanentFailure -> {
                    logger.w("Report ${report.id} rejected; kept on the device as failed", result.cause)
                    store.markFailed(report.id)
                }
                is SendResult.RetryableFailure -> {
                    logger.w("Report ${report.id} not delivered yet; will retry", result.cause)
                    return Outcome.RETRY
                }
            }
        }
        return Outcome.DONE
    }
}
