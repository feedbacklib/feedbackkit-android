package io.github.feedbacklib.android.internal.proactive

import android.util.AtomicFile
import io.github.feedbacklib.android.internal.core.SdkLogger
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The files of proactive reporting in `filesDir/feedbackkit/session/` (spec §8), each written whole
 * through [AtomicFile]: a run killed mid-write leaves the previous version, never half a file.
 * Blocking, and not safe for concurrent use: every read and write runs on the runtime's one serial
 * `sessionIo`, because on API 30+ `AtomicFile.openRead()` deletes a pending `<name>.new` and a
 * concurrent read would lose a write. The one exception is `crash.marker` on the crashing thread:
 * [writeCrashIfAbsent], and [deleteCrash] of that same marker when the process survives. Never throws: a
 * file that cannot be read is none, and a write that fails is logged and returns false.
 */
internal class SessionStore(private val dir: () -> File, private val logger: SdkLogger) {

    fun readSession(): SessionState? = read(SESSION_FILE, SessionJson::decodeSession)

    fun writeSession(state: SessionState): Boolean = write(SESSION_FILE, SessionJson.encodeSession(state))

    fun readCrash(): CrashMarker? = readCrashFile().marker

    /** The marker with whether its file was there at all: one that cannot be decoded is still there. */
    fun readCrashFile(): CrashFile = load(CRASH_FILE, SessionJson::decodeCrash).let { CrashFile(it.present, it.value) }

    /** Synchronous: the process is about to die. */
    fun writeCrash(marker: CrashMarker): Boolean = write(CRASH_FILE, SessionJson.encodeCrash(marker))

    /**
     * This run's marker, unless one is already there (spec §8): a marker still on disk has not been consumed
     * by the start-up detection — it is the previous run's and is never overwritten. Synchronous, on the
     * crashing thread. False when nothing was written. The check and the write are not one step: a crash
     * of this run that sees the previous run's marker while detection is still reading it leaves none, and
     * on API 30+ ApplicationExitInfo still finds that crash. The crash path takes no lock.
     */
    fun writeCrashIfAbsent(marker: CrashMarker): Boolean {
        if (crashPresent()) return false
        return writeCrash(marker)
    }

    private fun crashPresent(): Boolean =
        try {
            File(dir(), CRASH_FILE).exists()
        } catch (e: Exception) {
            false
        }

    /** Removes the marker, readable or not: markers go once processed (spec §8). */
    fun deleteCrash() {
        try {
            atomic(CRASH_FILE).delete()
        } catch (e: Exception) {
            logger.w("Could not delete $CRASH_FILE", e)
        }
    }

    fun readProactive(): ProactiveState? = read(PROACTIVE_FILE, SessionJson::decodeProactive)

    fun writeProactive(state: ProactiveState): Boolean = write(PROACTIVE_FILE, SessionJson.encodeProactive(state))

    private fun atomic(name: String): AtomicFile = AtomicFile(File(dir(), name))

    private fun <T : Any> read(name: String, decode: (String) -> T?): T? = load(name, decode).value

    /** A file read: [present] unless it was not there to open. */
    private class Loaded<T : Any>(val present: Boolean, val value: T?)

    private fun <T : Any> load(name: String, decode: (String) -> T?): Loaded<T> =
        try {
            val file = atomic(name)
            if (file.baseFile.length() > MAX_FILE_BYTES) {
                logger.w("$name is too large to be FeedbackKit's; ignored"); Loaded(true, null)
            } else {
                Loaded(true, decode(String(file.readFully(), Charsets.UTF_8)) ?: null.also { logger.w("$name could not be read; ignored") })
            }
        } catch (e: FileNotFoundException) { Loaded(false, null) } catch (e: Exception) { logger.w("Could not read $name", e); Loaded(true, null) }

    private fun write(name: String, text: String): Boolean =
        try {
            val directory = dir()
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create $directory")
            val file = atomic(name)
            val out = file.startWrite()
            try {
                out.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: IOException) {
                file.failWrite(out)
                throw e
            }
            finishReplaceIfLeftBehind(file.baseFile)
            true
        } catch (e: Exception) {
            logger.w("Could not write $name", e)
            false
        }

    /**
     * [AtomicFile.finishWrite] replaces the base file with `File.renameTo`, without checking its
     * result. On Windows that call does nothing when the base file already exists — it only renames
     * onto a missing target — so a second write leaves `<name>.new` behind with the new bytes and
     * the base file with the old ones. Finish that replace ourselves with an atomic NIO move, the
     * same tool this SDK already relies on for a replacing move elsewhere (`DraftStore.kt`): atomic
     * on Linux, Android and NTFS alike, so this is a no-op wherever [AtomicFile] already finished.
     */
    private fun finishReplaceIfLeftBehind(base: File) {
        val leftBehind = File(base.path + ".new")
        if (leftBehind.exists()) {
            Files.move(leftBehind.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    internal companion object {
        const val SESSION_FILE: String = "session.json"
        const val CRASH_FILE: String = "crash.marker"
        const val PROACTIVE_FILE: String = "proactive.json"

        /** Far above the largest file written here: a marker of 50 lines of 500 characters. */
        const val MAX_FILE_BYTES: Int = 65_536
    }
}

/** `crash.marker` as detection reads it: [present] tells a marker that could not be decoded from none. */
internal data class CrashFile(val present: Boolean, val marker: CrashMarker?)
