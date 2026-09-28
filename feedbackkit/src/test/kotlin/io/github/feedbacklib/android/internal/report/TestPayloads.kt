package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.ReportType

internal fun sampleDevice(): DeviceInfo = DeviceInfo(
    manufacturer = "Google",
    model = "Pixel 8",
    osVersion = "16",
    apiLevel = 36,
    locale = "ru-RU",
    orientation = "portrait",
    screen = "1080x2400@420",
    freeMemoryMb = 2048,
    freeDiskMb = 10240,
    networkType = "wifi",
)

internal fun sampleApp(): AppInfo = AppInfo(
    packageName = "com.example.app",
    versionName = "1.2.3",
    versionCode = 123,
    sdkVersion = "0.1.0",
)

internal fun samplePayload(
    id: String = "r-1",
    type: ReportType = ReportType.BUG,
    attachments: List<AttachmentMeta> = emptyList(),
): ReportPayload = ReportPayload(
    id = id,
    cid = "cid-1",
    type = type,
    createdAt = "2026-09-26T10:15:30Z",
    email = "user@example.com",
    comment = "It crashed",
    extended = null,
    proactive = null,
    tags = emptyList(),
    userAttributes = emptyMap(),
    userData = null,
    consoleLog = emptyList(),
    attachments = attachments,
    device = sampleDevice(),
    app = sampleApp(),
    currentScreen = "com.example.MainActivity",
)
