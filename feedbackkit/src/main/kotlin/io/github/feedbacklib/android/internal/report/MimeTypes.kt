package io.github.feedbacklib.android.internal.report

import java.net.URLConnection

internal object MimeTypes {

    // Our own table first: the JVM and Android disagree about webp, heic and mp4.
    private val KNOWN_TYPES = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "mp4" to "video/mp4",
    )

    /** Image types accepted from the photo picker, with the extension their draft file gets. */
    private val IMAGE_EXTENSIONS = mapOf(
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg", // non-standard, sent by some providers
        "image/x-png" to "png", // legacy alias
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/heic" to "heic",
        "image/heif" to "heif",
    )

    fun guess(fileName: String): String =
        KNOWN_TYPES[fileName.substringAfterLast('.', "").lowercase()]
            ?: URLConnection.guessContentTypeFromName(fileName)
            ?: "application/octet-stream"

    /** The draft extension for an accepted image [mimeType] (parameters ignored), or null. */
    fun imageExtension(mimeType: String?): String? =
        mimeType?.substringBefore(';')?.trim()?.lowercase()?.let(IMAGE_EXTENSIONS::get)
}
