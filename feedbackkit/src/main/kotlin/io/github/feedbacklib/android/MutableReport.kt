package io.github.feedbacklib.android

import java.io.File

/**
 * A report about to be queued, handed to [OnReportSubmitHandler]. The handler may add tags, log
 * lines, files, user attributes and user data; the draft fields are read-only.
 */
public interface MutableReport {
    public val type: ReportType
    public val email: String?
    public val comment: String
    public val tags: List<String>
    public val userAttributes: Map<String, String>
    public val userData: String?

    public fun addTag(tag: String)

    public fun appendConsoleLog(line: String)

    /** Attaches [file] under its own name. Read when the report is queued; at most 5 MB. */
    public fun addFile(file: File)

    /** Attaches [file] under [fileName]. Read when the report is queued; at most 5 MB. */
    public fun addFile(file: File, fileName: String)

    public fun setUserAttribute(key: String, value: String)

    public fun setUserData(data: String?)
}
