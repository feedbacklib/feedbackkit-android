package io.github.feedbacklib.android

import io.github.feedbacklib.android.internal.transport.HttpStatusMapping
import io.github.feedbacklib.android.internal.transport.MultipartWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.UUID

/**
 * Delivers reports with `POST {endpoint}/reports` as `multipart/form-data`. The contract is
 * described in `docs/backend-contract.md`. 2xx is success; 408, 429, 5xx, network errors and any
 * unexpected exception are retried; other 4xx are permanent.
 */
public class HttpReportSender internal constructor(
    endpoint: String,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
) : ReportSender {

    /** @param endpoint base URL, `http://` or `https://`; `/reports` is appended. */
    public constructor(endpoint: String) : this(endpoint, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS)

    private val reportsUrl: URL = endpoint.trimEnd('/').let { base ->
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "endpoint must be an http(s) URL: $endpoint"
        }
        try {
            URL("$base/reports")
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException("endpoint is not a valid URL: $endpoint", e)
        }
    }

    override suspend fun send(report: PreparedReport): SendResult = withContext(Dispatchers.IO) {
        try {
            post(report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.RetryableFailure(e)
        }
    }

    private fun post(report: PreparedReport): SendResult {
        val boundary = "FeedbackKit-${UUID.randomUUID()}"
        val connection = reportsUrl.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setChunkedStreamingMode(0)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty(HEADER_CID, report.cid)
            connection.setRequestProperty(HEADER_REPORT_ID, report.id)
            connection.setRequestProperty("User-Agent", "FeedbackKit/${FeedbackKitInfo.VERSION}")
            connection.outputStream.use { MultipartWriter(it, boundary).writeReport(report) }
            return HttpStatusMapping.toResult(connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 60_000
        const val HEADER_CID = "X-FeedbackKit-CID"
        const val HEADER_REPORT_ID = "X-FeedbackKit-Report-Id"
    }
}
