# Голос ТВ (ru.tvvoice)

Офлайн голосовой ассистент для Android TV (Xiaomi Mi TV 4A и похожих, Android 9, armeabi-v7a),
который перехватывает кнопку микрофона на пульте и исполняет русские команды
**полностью локально, без облака и без Google**.

## Возможности

| Команда | Действие |
|---|---|
| «открой ютуб» | запускает YouTube (Google YouTube TV) |
| «открой кинопоиск / иви / хэпп / сматрешку / твизавр / настройки» | запуск приложения по словарю синонимов |
| «включи бай дпи» | поднимает VPN ByeByeDPI (запускает приложение и нажимает тумблер через Accessibility) |
| «выключи бай дпи» | гасит VPN, если тот активен (проверяется статус на экране приложения) |
| «найди фильм дюна» | открывает [TV Bro](https://github.com/TrebSky/TVBro) с поиском Яндекса «… смотреть онлайн» |
| «открой браузер найди фильм <название>» | то же самое, комбинированная команда |

Распознавание — [Vosk](https://github.com/alphacephei/vosk-api) с офлайн-моделью
`vosk-model-small-ru-0.22` (модель запакована в `app/src/main/assets/`). Разбор команд
детерминированный (словарь синонимов + нечёткое сравнение по Левенштейну), LLM НЕТ.

## Как это работает

1. **Кнопка микрофона.** На прошивках Xiaomi Mic-кнопку система гонит в Google/Katniss в обход
   настройки `settings put secure assistant`. Поэтому перехват сделан через
   AccessibilityService с флагом `flagRequestFilterKeyEvents`: реальное нажатие
   `KEYCODE_VOICE_ASSIST` (231) / `KEYCODE_ASSIST` (219) перехватывается раньше диспетчера,
   съедается и открывает окно ассистента. Параллельно приложение зарегистрировано как
   штатный `VoiceInteractionService` (план A — работает на прошивках, где кнопка идёт по фреймворку).
2. **Распознавание.** `AudioRecord` 16 кГц → Vosk streaming → финальный текст.
3. **Исполнение команд.** Словарь `APP_ALIASES` в `Engine.java`: пакеты приложений и синонимы
   («бай дпи», «байдпи», «dpi», …). Для ByeByeDPI статус читается из `status_text` его UI и
   `status_button_card` кликается через accessibility — согласие на VPN выдаётся один раз вручную.
4. **Отладочный канал.** Тест команды без голоса:
   ```bash
   adb shell am start -n ru.tvvoice/.MainActivity --es tb64 <base64 от utf-8 команды> --activity-clear-task
   ```

## Установка (один раз, только ADB, root не нужен)

```bash
adb install app-release.apk
adb shell pm grant ru.tvvoice android.permission.RECORD_AUDIO
adb shell settings put secure assistant ru.tvvoice/ru.tvvoice.voice.VoxService
adb shell settings put secure voice_interaction_service ru.tvvoice/ru.tvvoice.voice.VoxService
adb shell settings put secure enabled_accessibility_services ru.tvvoice/.voice.VoxAccessibility
adb shell settings put secure accessibility_enabled 1
reboot   # активирует accessibility-службу
```

Запустить приложение «Голос ТВ» один раз — модель распакуется и прогреется в фоне.

## Сборка

```bash
./gradlew assembleRelease   # подписывается debug-подписью, APK ~60 МБ (внутри модель)
```

Модель заменяется подстановкой другого `.zip` в `app/src/main/assets/`
(список моделей: <https://alphacephei.com/vosk/models>).

## Зарегистрированные команды (словарь)

`ютуб`, `браузер`, `кинопоиск`, `иви`, `твизавр`, `сматрешка`, `хэпп`, `дпи`, `настройки`.
Глаголы: открыть/включи/запусти, выключи/закрой/стоп/стоп-дпи. Расширение словаря —
массив `APPS` в `Engine.java`.

## Структура

```
app/src/main/java/ru/tvvoice/
  Engine.java              — разбор и исполнение команд
  ModelProvider.java       — распаковка/прогрев VOSK-модели
  MainActivity.java        — статусы, отладочный канал
  AssistActivity.java      — оверлей ассистента (план B, перехват кнопки)
  voice/VoxService.java    — VoiceInteractionService (план A)
  voice/VoxSession*.java   — штатная сессия ассистента
  voice/VoxAccessibility.java — фильтр кнопки микрофона + клик/статус ByeByeDPI
  voice/RecognizerThread.java — AudioRecord → VOSK
```

## Лицензии

Vosk — Apache 2.0, модель `vosk-model-small-ru-0.22` — Apache 2.0 (<https://alphacephei.com/vosk/vendors/licenses>).
TV Bro — MIT; ByeByeDPI — отдельная лицензия авторов (https://github.com/hufrea/byedpi).
