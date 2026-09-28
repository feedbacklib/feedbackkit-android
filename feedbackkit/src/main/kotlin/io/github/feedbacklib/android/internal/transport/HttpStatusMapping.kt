package io.github.feedbacklib.android.internal.transport

import io.github.feedbacklib.android.SendResult
import java.io.IOException

internal object HttpStatusMapping {

    fun toResult(code: Int): SendResult = when {
        code in 200..299 -> SendResult.Success
        code < 0 || code == 408 || code == 429 || code in 500..599 ->
            SendResult.RetryableFailure(IOException("HTTP $code"))
        else -> SendResult.PermanentFailure(IOException("HTTP $code"))
    }
}
