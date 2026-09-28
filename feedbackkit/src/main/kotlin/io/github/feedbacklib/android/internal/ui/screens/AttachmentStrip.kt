package io.github.feedbacklib.android.internal.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.TextKey
import io.github.feedbacklib.android.internal.report.DraftFile
import io.github.feedbacklib.android.internal.report.isVideo
import io.github.feedbacklib.android.internal.ui.AttachNotice
import io.github.feedbacklib.android.internal.ui.LocalTexts
import io.github.feedbacklib.android.internal.ui.ReportActions
import io.github.feedbacklib.android.internal.ui.ReportUiState
import io.github.feedbacklib.android.internal.ui.ThumbnailCache
import io.github.feedbacklib.android.internal.ui.ThumbnailKey
import io.github.feedbacklib.android.internal.ui.decodeThumbnail
import io.github.feedbacklib.android.internal.ui.decodeVideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The attachments of the form (spec §6): the placeholder of a missing invocation screenshot,
 * thumbnails with a remove badge, the tiles that add more while fewer than four are attached, the
 * limit caption once four are, and why the last add did nothing.
 */
@Composable
internal fun AttachmentStrip(state: ReportUiState, actions: ReportActions) {
    val hasRow = state.attachments.isNotEmpty() || state.screenshotUnavailable || state.canAddGalleryImage ||
        state.canAddExtraScreenshot || state.canRecordScreen || state.autoClipPending
    if (!hasRow && !state.attachmentLimitReached && state.notice == null) return
    val rowState = rememberLazyListState()
    // A LazyRow holds on to its first visible item, so attachments that arrive in front of it (the
    // draft loaded after the row showed, a recording back from the host) would stay out of sight.
    // Bring the first new one into view instead.
    val keys = state.attachments.map { it.file.path }
    val seen = remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(keys) {
        val before = seen.value
        seen.value = keys.toSet()
        if (before == null) return@LaunchedEffect // what the row opens with is already in place
        val firstNew = keys.indexOfFirst { it !in before }
        if (firstNew >= 0) rowState.animateScrollToItem(firstNew + if (state.screenshotUnavailable) 1 else 0)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        if (hasRow) {
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ReportTestTags.ATTACHMENTS),
            ) {
                if (state.screenshotUnavailable) item(key = "unavailable") { ScreenshotUnavailableCard() }
                items(state.attachments, key = { it.file.path }) { AttachmentThumbnail(it, actions) }
                // Where the automatic recording will show: it sorts after every other attachment.
                if (state.autoClipPending) item(key = "autoClipPending") { AutoClipPendingCard() }
                if (state.canAddExtraScreenshot) {
                    item(key = "addScreenshot") {
                        AddAttachmentTile(
                            icon = R.drawable.feedbackkit_ic_add_screenshot,
                            label = stringResource(R.string.feedbackkit_add_screenshot),
                            tag = ReportTestTags.ADD_SCREENSHOT,
                            onClick = actions::onAddExtraScreenshot,
                        )
                    }
                }
                if (state.canRecordScreen) {
                    item(key = "recordScreen") {
                        AddAttachmentTile(
                            icon = R.drawable.feedbackkit_ic_record_screen,
                            label = stringResource(R.string.feedbackkit_record_screen),
                            tag = ReportTestTags.RECORD_SCREEN,
                            enabled = !state.recordingStarting,
                            onClick = actions::onRecordScreen,
                        )
                    }
                }
                if (state.canAddGalleryImage) {
                    item(key = "addImage") {
                        AddAttachmentTile(
                            icon = R.drawable.feedbackkit_ic_add_image,
                            label = stringResource(R.string.feedbackkit_add_image),
                            tag = ReportTestTags.ADD_IMAGE,
                            onClick = actions::onAddGalleryImage,
                        )
                    }
                }
            }
        }
        // Suppressed while the notice already says the same thing (the last add was refused for
        // hitting this same limit): otherwise "Up to 4 attachments" would show twice at once.
        if (state.attachmentLimitReached && state.notice != AttachNotice.LIMIT_REACHED) {
            Text(
                stringResource(R.string.feedbackkit_attachment_limit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(ReportTestTags.ATTACHMENT_LIMIT),
            )
        }
        state.notice?.let { notice ->
            Text(
                noticeText(notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(ReportTestTags.ATTACH_NOTICE),
            )
        }
    }
}

@Composable
private fun noticeText(notice: AttachNotice): String = when (notice) {
    AttachNotice.SCREENSHOT_UNAVAILABLE -> LocalTexts.current[TextKey.SCREENSHOT_UNAVAILABLE]
    AttachNotice.LIMIT_REACHED -> stringResource(R.string.feedbackkit_attachment_limit)
    AttachNotice.IMAGE_TOO_LARGE -> stringResource(R.string.feedbackkit_notice_image_too_large)
    AttachNotice.IMAGE_UNSUPPORTED -> stringResource(R.string.feedbackkit_notice_image_unsupported)
    AttachNotice.IMAGE_FAILED -> stringResource(R.string.feedbackkit_notice_image_failed)
    AttachNotice.RECORDING_FAILED -> stringResource(R.string.feedbackkit_notice_record_failed)
}

/** A tile the height of a thumbnail that adds an attachment; its label is its accessible name. */
@Composable
internal fun AddAttachmentTile(@DrawableRes icon: Int, label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(top = RemoveInset) // level with the thumbnails' images
            .width(96.dp)
            .semantics { role = Role.Button }
            .testTag(tag),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .heightIn(min = ThumbnailHeight)
                .padding(8.dp)
                // Disabled while a consent dialog is open (spec §7): dimmed like any disabled control,
                // the standard Material content alpha rather than a colour of its own.
                .alpha(if (enabled) 1f else DisabledContentAlpha),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ScreenshotUnavailableCard() {
    val texts = LocalTexts.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(top = RemoveInset) // level with the thumbnails' images
            .width(120.dp)
            .heightIn(min = ThumbnailHeight)
            .testTag(ReportTestTags.SCREENSHOT_UNAVAILABLE),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
            Text(
                texts[TextKey.SCREENSHOT_UNAVAILABLE],
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The automatic recording still being made (spec §7): the size of a thumbnail, with a spinner, until
 * the clip takes its place. Nothing to tap; its description is read out.
 */
@Composable
private fun AutoClipPendingCard() {
    val description = stringResource(R.string.feedbackkit_auto_recording_preparing)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(top = RemoveInset, end = RemoveInset) // level with the thumbnails' images
            .width(90.dp)
            .height(ThumbnailHeight)
            .semantics { contentDescription = description }
            .testTag(ReportTestTags.AUTO_CLIP_PENDING),
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
    }
}

/**
 * A thumbnail with a small remove badge in its top-end corner. The badge's 48 dp touch target reaches
 * [RemoveInset] beyond the image, so it covers only a corner of it: a portrait screenshot is mostly left for the eye, and a tap on the
 * picture itself removes nothing.
 */
@Composable
private fun AttachmentThumbnail(file: DraftFile, actions: ReportActions) {
    val heightPx = with(LocalDensity.current) { ThumbnailHeight.roundToPx() }
    val key = ThumbnailKey.of(file, heightPx)
    val video = file.kind.isVideo
    val bitmap by produceState<ImageBitmap?>(ThumbnailCache.get(key), key) {
        value = ThumbnailCache.get(key) ?: withContext(Dispatchers.IO) {
            if (video) decodeVideoFrame(file.file, heightPx) else decodeThumbnail(file.file, heightPx)
        }?.also { ThumbnailCache.put(key, it) }
    }
    Box(Modifier.testTag(ReportTestTags.attachment(file.fileName))) {
        val frame = Modifier
            .padding(top = RemoveInset, end = RemoveInset)
            .height(ThumbnailHeight)
            .clip(RoundedCornerShape(12.dp))
        // Images open the editor; a recording has none (spec §7). The remove badge is a button of its own.
        val image = if (video) {
            frame
        } else {
            frame.clickable(onClickLabel = stringResource(R.string.feedbackkit_annotation_edit)) { actions.onEditAttachment(file) }
        }
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = file.description(),
                contentScale = ContentScale.Fit,
                modifier = image,
            )
        } else {
            Box(
                image
                    .width(90.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        if (video) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 8.dp)
                    .size(28.dp)
                    .testTag(ReportTestTags.ATTACHMENT_VIDEO_BADGE),
            ) {
                Icon(painterResource(R.drawable.feedbackkit_ic_videocam), contentDescription = null, modifier = Modifier.padding(5.dp))
            }
        }
        IconButton(
            onClick = { actions.onRemoveAttachment(file) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .size(RemoveBadgeSize)
                    .testTag(ReportTestTags.ATTACHMENT_REMOVE_BADGE),
            ) {
                Icon(
                    painterResource(R.drawable.feedbackkit_ic_delete),
                    contentDescription = file.removeDescription(),
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun DraftFile.description(): String = stringResource(
    when (kind) {
        AttachmentKind.SCREENSHOT -> R.string.feedbackkit_attachment_screenshot
        AttachmentKind.EXTRA_SCREENSHOT -> R.string.feedbackkit_attachment_extra_screenshot
        AttachmentKind.SCREEN_RECORDING -> R.string.feedbackkit_attachment_screen_recording
        AttachmentKind.AUTO_SCREEN_RECORDING -> R.string.feedbackkit_attachment_auto_recording
        else -> R.string.feedbackkit_attachment_gallery
    },
)

@Composable
private fun DraftFile.removeDescription(): String = stringResource(
    when (kind) {
        AttachmentKind.SCREENSHOT, AttachmentKind.EXTRA_SCREENSHOT -> R.string.feedbackkit_attachment_remove
        AttachmentKind.SCREEN_RECORDING, AttachmentKind.AUTO_SCREEN_RECORDING -> R.string.feedbackkit_attachment_remove_recording
        else -> R.string.feedbackkit_attachment_remove_image
    },
)

internal val ThumbnailHeight = 160.dp

private val RemoveBadgeSize = 24.dp

/** How far the remove button's 48 dp touch target reaches beyond the image: half of it minus half the badge. */
internal val RemoveInset = (48.dp - RemoveBadgeSize) / 2

/** Material's standard content alpha for a disabled control. */
private const val DisabledContentAlpha = 0.38f
