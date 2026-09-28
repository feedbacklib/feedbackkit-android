package io.github.feedbacklib.android.internal.recording

import android.content.Context
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.spi.RecorderLog
import io.github.feedbacklib.android.spi.ScreenRecorder
import io.github.feedbacklib.android.spi.ScreenRecorderProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * The screen recorder `feedbackkit-recording` provides, if the host added it (spec §3). Found once
 * with ServiceLoader, which reads the APK's service files: [discover] is disk I/O, background only.
 * No recorder means the recording UI stays hidden. A provider of another contract version, one that
 * throws, or a broken services entry is skipped with a warning; the first usable one wins.
 *
 * A `feedbackkit-recording` of another version can fail to link (NoClassDefFoundError,
 * AbstractMethodError, …) anywhere ServiceLoader or the provider touches its classes. Those are
 * Errors, which the startup pass does not catch, so every such step is guarded here: the lookup
 * never throws them, recording just stays off.
 */
internal class Recorders(
    private val context: Context,
    private val logger: SdkLogger,
    private val providers: () -> Iterator<ScreenRecorderProvider> = ::serviceLoaderProviders,
) {
    private val found = MutableStateFlow(false)

    /** Whether a recorder exists; false until [discover] ran. */
    val available: StateFlow<Boolean> = found

    @Volatile
    var recorder: ScreenRecorder? = null
        private set

    /** Whether [discover] ran: before it, a missing [recorder] means "not known yet". */
    @Volatile
    var discovered: Boolean = false
        private set

    @Synchronized
    fun discover() {
        if (discovered) return
        try {
            recorder = firstUsable()
        } finally {
            discovered = true
            found.value = recorder != null
        }
    }

    private fun firstUsable(): ScreenRecorder? {
        val iterator = try {
            providers()
        } catch (e: ServiceConfigurationError) {
            logger.w("The screen recorder services file could not be read; recording is off", e)
            return null
        } catch (e: LinkageError) {
            logger.w("feedbackkit-recording does not match this FeedbackKit; use the same version of both artifacts. Recording is off", e)
            return null
        } catch (e: Exception) {
            // In a minified host R8 turns ServiceLoader.load(...).iterator() into direct construction of
            // the listed providers, so a provider constructor that throws fails here, not in next().
            logger.w("The screen recorder could not be created; recording is off", e)
            return null
        }
        repeat(MAX_ENTRIES) {
            val provider = try {
                if (!iterator.hasNext()) return null
                iterator.next()
            } catch (e: ServiceConfigurationError) {
                logger.w("A screen recorder entry could not be loaded; skipped", e)
                return@repeat
            } catch (e: LinkageError) {
                logger.w("A screen recorder entry does not match this FeedbackKit; use the same version of both artifacts", e)
                return@repeat
            }
            try {
                if (provider.spiVersion != ScreenRecorderProvider.SPI_VERSION) {
                    logger.w(
                        "feedbackkit-recording was built for another FeedbackKit (contract ${provider.spiVersion}, " +
                            "expected ${ScreenRecorderProvider.SPI_VERSION}); use the same version of both artifacts. Recording is off",
                    )
                    return@repeat
                }
                return provider.create(context, RecorderLog { level, message, error -> logger.log(level, message, error) })
            } catch (e: Exception) {
                logger.w("The screen recorder could not start; skipped", e)
            } catch (e: LinkageError) {
                logger.w("feedbackkit-recording does not match this FeedbackKit; use the same version of both artifacts", e)
            }
        }
        return null
    }

    private companion object {
        /** A broken iterator that throws forever must not spin: far more entries than will ever exist. */
        const val MAX_ENTRIES = 16
    }
}

/**
 * The real lookup. Keep the `ServiceLoader.load(X::class.java, X::class.java.classLoader)` shape:
 * R8 recognises exactly it and, in a minified host, replaces it with direct construction of the
 * providers listed in META-INF/services.
 */
internal fun serviceLoaderProviders(): Iterator<ScreenRecorderProvider> =
    ServiceLoader.load(ScreenRecorderProvider::class.java, ScreenRecorderProvider::class.java.classLoader).iterator()
