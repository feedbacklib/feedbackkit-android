# Контракт приёма отчётов (черновик)

Статус: черновик для будущего бэкенда (спека §9). Клиент — `HttpReportSender`.

## Запрос

`POST {endpoint}/reports`, `Content-Type: multipart/form-data; boundary=…`

| Заголовок | Значение |
|---|---|
| `X-FeedbackKit-CID` | идентификатор клиента, заданный в `FeedbackKit.Builder(app, cid)` |
| `X-FeedbackKit-Report-Id` | UUID отчёта; тот же, что `id` в JSON |
| `User-Agent` | `FeedbackKit/<версия SDK>` |

Части тела, в этом порядке:

1. `name="report"`, `Content-Type: application/json; charset=utf-8` — `report.json` (ниже).
2. По одной части на вложение: `name="<attachment.id>"; filename="<fileName>"`,
   `Content-Type` — `attachment.mimeType`. Имя части совпадает с `attachments[].id` в JSON.

Тело передаётся chunked, без `Content-Length`.

## `report.json` (schemaVersion 1)

| Поле | Тип | Описание |
|---|---|---|
| `schemaVersion` | int | версия схемы, сейчас `1` |
| `id` | string (UUID) | генерирует клиент |
| `cid` | string | как в заголовке |
| `type` | enum | `BUG`, `FEEDBACK`, `QUESTION`, `FRUSTRATING_EXPERIENCE` |
| `createdAt` | string | ISO-8601 UTC, например `2026-09-26T10:15:30Z` |
| `email` | string\|null | |
| `comment` | string | может быть пустой |
| `extended` | object\|null | `steps`, `actual`, `expected` (строки) — только для `BUG` |
| `proactive` | object\|null | `trigger` (`CRASH`\|`FORCE_RESTART`), `detectedAt`, `exception`\|null, `stacktrace`\|null |
| `tags` | string[] | |
| `userAttributes` | object<string,string> | |
| `userData` | string\|null | |
| `consoleLog` | string[] | строки, добавленные приложением |
| `attachments` | object[] | `id`, `kind` (`SCREENSHOT`, `EXTRA_SCREENSHOT`, `GALLERY_IMAGE`, `SCREEN_RECORDING`, `AUTO_SCREEN_RECORDING`, `APP_FILE`), `fileName`, `mimeType`, `sizeBytes` |
| `device` | object | `manufacturer`, `model`, `osVersion`, `apiLevel`, `locale`, `orientation` (`portrait`\|`landscape`\|`undefined`), `screen` (`WxH@dpi`), `freeMemoryMb`, `freeDiskMb`, `networkType` (`wifi`\|`cellular`\|`ethernet`\|`vpn`\|`other`\|`none`\|`unknown`) |
| `app` | object | `packageName`, `versionName`\|null, `versionCode`, `sdkVersion` |
| `currentScreen` | string\|null | класс Activity, где вызвали форму |

Сервер должен игнорировать неизвестные поля: новые поля добавляются без смены `schemaVersion`,
смена версии — только при несовместимых изменениях.

## Ответ

| Код | Что делает клиент |
|---|---|
| `2xx` | отчёт доставлен, удаляется с устройства |
| `408`, `429`, `5xx`, обрыв сети | повтор позже (экспоненциальная задержка от 30 с, WorkManager) |
| прочие `4xx` | отчёт помечается `failed`, больше не отправляется |

Отчёт удаляется с устройства через 14 дней после создания независимо от статуса — как ещё
не отправленный (`pending`), так и `failed`: локальная очередь ограничена по возрасту, числу
отчётов (20) и суммарному размеру (100 МБ) вне зависимости от ответа сервера. Так что бэкенду
не стоит полагаться на то, что клиент будет повторять доставку дольше 14 дней.

Клиент доставляет отчёты строго в порядке создания (oldest-first): повторяемый ответ (`408`,
`429`, `5xx`) на один отчёт останавливает весь текущий проход по очереди, и следующие за ним
отчёты не отправляются до следующего прохода. Поэтому отчёт, который сервер не сможет принять
никогда, должен получить `4xx`, а не повторяемую ошибку — иначе он блокирует очередь клиента
на срок до 14 дней.

Тело ответа клиент не читает.

## Идемпотентность

Клиент может отправить один и тот же отчёт повторно (например, ответ потерялся). Повтор с уже
принятым `X-FeedbackKit-Report-Id` сервер должен принимать как дубль и отвечать `2xx`,
не создавая второй отчёт.

Машиночитаемая версия — `docs/openapi.yaml`.
