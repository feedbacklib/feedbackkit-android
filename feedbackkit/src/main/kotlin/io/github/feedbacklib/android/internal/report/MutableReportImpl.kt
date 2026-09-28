package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.MutableReport
import io.github.feedbacklib.android.ReportType
import java.io.File

internal class MutableReportImpl(
    override val type: ReportType,
    override val email: String?,
    override val comment: String,
) : MutableReport {

    private val lock = Any()
    private val tagList = mutableListOf<String>()
    private val logLines = mutableListOf<String>()
    private val files = mutableListOf<AddedFile>()
    private val attributes = linkedMapOf<String, String>()
    private var data: String? = null

    override val tags: List<String>
        get() = synchronized(lock) { tagList.toList() }

    override val userAttributes: Map<String, String>
        get() = synchronized(lock) { attributes.toMap() }

    override val userData: String?
        get() = synchronized(lock) { data }

    override fun addTag(tag: String) {
        synchronized(lock) {
            if (tag.isNotBlank() && tag !in tagList) tagList += tag
        }
    }

    override fun appendConsoleLog(line: String) {
        synchronized(lock) { logLines += line }
    }

    override fun addFile(file: File) = addFile(file, file.name)

    override fun addFile(file: File, fileName: String) {
        synchronized(lock) { files += AddedFile(file, fileName) }
    }

    override fun setUserAttribute(key: String, value: String) {
        synchronized(lock) { attributes[key] = value }
    }

    override fun setUserData(data: String?) {
        synchronized(lock) { this.data = data }
    }

    /** What the handler added, frozen at the moment of the call. */
    fun additions(): ReportAdditions = synchronized(lock) {
        ReportAdditions(tagList.toList(), logLines.toList(), files.toList(), attributes.toMap(), data)
    }

    data class AddedFile(val file: File, val fileName: String)
}

internal data class ReportAdditions(
    val tags: List<String>,
    val consoleLog: List<String>,
    val files: List<MutableReportImpl.AddedFile>,
    val userAttributes: Map<String, String>,
    val userData: String?,
) {
    companion object {
        val EMPTY: ReportAdditions = ReportAdditions(emptyList(), emptyList(), emptyList(), emptyMap(), null)
    }
}
