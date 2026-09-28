# FeedbackKit

In-app bug reporting SDK for Android: users shake the device (or take a screenshot, tap a floating
button, swipe with two fingers) to send a bug report, feedback or a question with an annotated
screenshot, extra images and a screen recording. After a crash, an ANR or a force restart it can ask
on the next start what happened (proactive reporting, off by default); crashes themselves are never
sent.

Status: early development (stage 7 of 8 — proactive reporting).

## Modules

- `feedbackkit` — the SDK.
- `feedbackkit-recording` — optional screen recording: "Record screen" in the report form (consent
  every time, up to 60 s) and Auto Screen Recording [beta — for internal testing only]. Adds
  FOREGROUND_SERVICE_MEDIA_PROJECTION, POST_NOTIFICATIONS and a mediaProjection service to the
  host's manifest (FOREGROUND_SERVICE comes with WorkManager anyway), which needs a Play Console declaration. Private views are not
  masked in video.
- `sample` — demo app.

## Build

See `AGENTS.md` for the toolchain and build rules, `PUBLISHING.md` for artifacts.
