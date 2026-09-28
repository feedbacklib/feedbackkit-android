package io.github.feedbacklib.android.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Stands in for the photo picker in device tests: `content://<AUTHORITY>/<name>` serves
 * `cacheDir/test-images/<name>` as `image/jpeg`, the way a picked gallery photo arrives.
 */
class TestImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())
        val file = File(dir(context!!.cacheDir), File(name).name)
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY: String = "io.github.feedbacklib.android.test.images"

        fun dir(cacheDir: File): File = File(cacheDir, "test-images")

        fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }
}
