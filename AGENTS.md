# FeedbackKit — правила проекта

Android SDK баг-репортинга.

## Процесс

- Каждый этап: реализация по задачам (TDD) → проверка.
- Изменение, противоречащее спеке, — сначала правка спеки с согласия владельца.
- Ни одно «готово» без вывода успешной сборки и тестов.

## Модули

- `:feedbackkit` — основной AAR (core + UI).
- `:feedbackkit-recording` — AAR записи экрана (MediaProjection); отдельно, чтобы его разрешения
  не попадали в хост без явного подключения; реализация контракта `spi` находится через
  `ServiceLoader`, публичного API нет.
- `:sample` — демо-приложение; каждая фича должна быть проверяема в нём руками.
- `build-logic/` — included build с convention-плагином `feedbackkit.abi-validation` (ABI-дамп
  поверх `org.jetbrains.kotlin:abi-tools`, пока KT-83410 не даёт использовать встроенную
  валидацию KGP); там же задача `GenerateSdkVersionTask` — версия SDK как
  `internal const` вместо `BuildConfig`; конвеншн-плагин `feedbackkit.android-library` —
  общее для обеих библиотек (координаты, compileSdk/minSdk, Java 11, публикация); `kotlin {}`
  остаётся в модулях.

## Жёсткие правила

- Тулчейн = основное хост-приложение: AGP 9.4.0, Kotlin 2.4.20, Gradle 9.6.1, compileSdk 37,
  minSdk 26. Меняется только вместе с ним.
- Байткод библиотек — Java 11 (`compileOptions` + `jvmTarget`).
- Kotlin компилирует AGP (built-in Kotlin): плагин `org.jetbrains.kotlin.android` **не применять**,
  он объявлен в корне `apply false` только ради версии KGP.
- `explicitApi()`: публичное — только намеренно; всё остальное `internal`.
- Контракт между артефактами — пакет `io.github.feedbacklib.android.spi`: `public` ради `:feedbackkit-recording`,
  но каждое объявление под `@FeedbackKitSpi` (opt-in уровня ERROR) и `@RestrictTo(LIBRARY_GROUP)`;
  несовместимое изменение поднимает `ScreenRecorderProvider.SPI_VERSION`; у `:feedbackkit-recording`
  публичного API нет.
- Изменил публичное API — обнови дамп: `./gradlew updateAbi`; проверка `./gradlew checkAbi` входит
  в `check`; дифф `.api` — часть ревью.
- Все версии — в `gradle/libs.versions.toml`, без `+`/`latest`, без `files(...)`.
- Новые зависимости библиотек — только с обоснованием: каждая попадает в POM потребителя.
- SDK никогда не роняет хост: исключения из системных колбэков и колбэков хоста ловятся и логируются.
- Имена чужих продуктов баг-репортинга (список — в локальной спеке) запрещены в коде, ресурсах,
  артефактах и документации.
- В тестах не использовать Kotlin-inline-хелперы JUnit (`assertDoesNotThrow {}`, `assertThrows<T> {}`
  из `org.junit.jupiter.api`): JUnit 6 собран под JVM 17 и не инлайнится в код с `jvmTarget 11`.
  Использовать Java-API `org.junit.jupiter.api.Assertions`.
- Тесты: чистая логика — JUnit 5 (`@TempDir`); код с Android-классами — JUnit 4 + Robolectric
  (`@RunWith(RobolectricTestRunner::class)`, SDK 35 из `src/test/resources/robolectric.properties`),
  запускается через vintage engine вместе с JUnit 5.
- Коммиты — без ИИ-трейлеров (`Co-Authored-By`, `Claude-Session`). Публикация — `origin` = `github.com/feedbacklib/feedbackkit-android`, пушится только `main`; внутренние имена компании и хост-приложения в репу не попадают.
- Видимые строки SDK — ресурсы `values/` + `values-ru/` (lint `MissingTranslation` ловит пропуск);
  текст, который хост может заменить, получает ключ в `TextKey` (публичное API — через `updateAbi`).
- Префиксы ресурсов: `:feedbackkit` — `feedbackkit_`, но никогда `feedbackkit_recording_`;
  `:feedbackkit-recording` — `feedbackkit_recording_`.
- Файлы черновика трогаются только на `DraftStore.queue(draftId)` / `onQueue`: одна последовательная
  очередь на черновик для всех экранов и компонентов; единственная работа вне очереди — стартовые
  обходы `pendingCapture` и `purgeStale`; переносы — через `Files.move`, без предварительного
  удаления цели.
- Пиксельные операции редактора — в `internal/annotate` над `PixelSurface` (чистый JVM, тесты JUnit 5
  до пикселя); Android-адаптер (`BitmapSurface`, декодирование) тонкий и проверяется на устройстве.
- Значения времени сборки — `internal const` из `GenerateSdkVersionTask`, не `buildConfigField`
  (`buildConfig` выключен).
- Запись экрана: `MediaRecorder`, `MediaProjection` и `VirtualDisplay` — только на потоке своей
  сессии, всё там под `guard`; порядок «согласие → `startForeground` → `getMediaProjection`» держит
  `SessionMachine` — не обходить.
- Proactive: файлы `filesDir/feedbackkit/session/` — только через `SessionStore` (`AtomicFile`);
  `CrashHandler` всегда ровно один раз вызывает предыдущий хендлер, на падающем потоке пишется только
  `crash.marker`; эвристика — чистая `ProactiveDetector.detect` с таблицей в JUnit 5.

## Сборка

Gradle — синхронно, `--console=plain`, вывод в файл, **никогда не в пайп** (демон держит пайп,
команда виснет):

```bash
LOG="$TEMP/feedbackkit-build.log"
./gradlew build --console=plain > "$LOG" 2>&1 < /dev/null; echo "EXIT=$?"; tail -30 "$LOG"
```

| Задача | Команда |
|---|---|
| Unit-тесты | `./gradlew :feedbackkit:testDebugUnitTest` |
| Unit-тесты записи | `./gradlew :feedbackkit-recording:testDebugUnitTest` |
| Сквозной тест на эмуляторе/устройстве (нужен запущенный эмулятор или устройство) | `./gradlew :feedbackkit:connectedDebugAndroidTest` |
| Запись экрана на эмуляторе/устройстве (согласие жмёт UiAutomator) | `./gradlew :feedbackkit-recording:connectedDebugAndroidTest` |
| Всё (тесты, lint, ABI) | `./gradlew build` |
| Публикация локально | `./gradlew publishToMavenLocal` |
| Sample APK | `./gradlew :sample:assembleDebug` |
| ABI-дамп (обновить / проверить) | `./gradlew updateAbi` / `./gradlew checkAbi` |

`local.properties` (не в git): `sdk.dir=C\:\\Workspace\\android-sdk`.

Запуск sample на устройстве: `adb install -r sample/build/outputs/apk/debug/sample-debug.apk`.
