# План: кнопка микрофона → «Голос ТВ» (продолжение)

ТВ Xiaomi Mi TV 4A — 192.168.3.61 (adb over TCP, порт 5555). Второй ТВ — 192.168.3.62.

## Состояние
- Ассистент (`ru.tvvoice`) установлен, Vosk-модель работает, NLU v2 проверен через tb64-канал (open/search/toggle byedpi — всё ок).
- Система принимает ассистента: `dumpsys voiceinteraction` → mComponent=ru.tvvoice/.voice.VoxService (VALID).
  Исправлено: permission `BIND_VOICE_INTERACTION` (не BIND_VOICE_SERVICE!), meta-data `android.voice_interaction` (не android.service.voice), свой DummyRecognitionService + recognitionService в @xml/voice_service_config.
- AssistActivity — получатель `android.speech.action.WEB_SEARCH` и `android.intent.action.ASSIST`.
- **Katniss отключена**: `pm disable-user --user 0 com.google.android.katniss` (включить обратно: `pm enable ...`).

## Главный след (с чего начать завтра)
1. ФИЗИЧЕСКИЙ пульт шлёт **keycode 219 (KEYCODE_ASSIST)**, а не 231! Проверено в логе:
   `interceptKeyTq keycode=219 ... policyFlags=22000000` — при нажатии физической кнопки микрофона.
   (getevent показывает KEY_VOICECOMMAND, но Xiaomi keylayout ремапит в 219.)
   Инжектированный `input keyevent 231` — 2b000000 — стандартный путь → WEB_SEARCH → открывается AssistActivity.
   Физический 219 → система НИЧЕГО не запускает (ни ASSIST, ни WEB_SEARCH) при отключённой Katniss.
2. Гипотезы для проверки:
   - PMW на 219 зовёт что-то через VoiceInteractionManagerService.showSession с условиями (screen/interactive) — смотреть `logcat -v brief` в момент нажатия (жать кнопку, пока снят лог: `adb shell logcat -v time > log.txt`).
   - Проверить `input keyevent 219` (инжектированный) — открывает ли AssistActivity; если да — проблема в policyFlags физического ключа.
   - Проверить, что резолвится на `android.intent.action.ASSIST` (`pm query-activities` / dumpsys package).
   - Возможно, ROM требует у «ассистента» activity с category DEFAULT для ASSIST + `supports_assist` + включённый VIS — всё вроде есть.
   - Крайний вариант: MicTV перехват через отдельный сервис работает только при включённом Katniss → попробовать включить Katniss обратно и смотреть, куда уходит 219 (`logcat` покажет START ... katniss) — тогда подменим точку входа иначе (например, вернуть accessibility-фильтр, но床位 уже не работает...).

## Что уже сделано по микрофону (контекст)
- RecognizerThread v3: проба уровня звука (peak) на источниках VR/MIC/DEFAULT, затем Bluetooth SCO фолбэк:
  setMode(MODE_IN_COMMUNICATION) + startBluetoothSco + setPreferredDevice(RC-устройство). Логи в history.log:
  `MIC src=... route=... peak=...`, `MIC2 src=... sco=... route=... peak=...`
- Результат пробы: `route=wired/Xiaomi RC peak=0` — вход маршрутизируется на пульт, но аудио нулевое
  (SCO не поднимался: устройства TYPE_BLUETOOTH_SCO не было в списке, пульт числится «wired»).
  → завтра проверить под SCO: появился ли на аппаратном уровне SCO-канал (логи AudioPolicyManager/BluetoothHeadsetService при startBluetoothSco).
- Колбэк UI из RecognizerThread — все через runOnUiThread (CalledFromWrongThreadException исправлен).
- Журнал распознавания на ТВ: `/sdcard/Android/data/ru.tvvoice/files/history.log` (читать: `adb -s 192.168.3.61:5555 exec-out 'cat ...'`).

## Полезные команды
- Сборка: JAVA_HOME=$TEMP\opencode\jdk17\jdk-17.0.20.1+1, ANDROID_HOME=$TEMP\opencode\android-sdk,
  `gradlew.bat assembleRelease`, APK: app\build\outputs\apk\release\app-release.apk, `adb install -r`
- Доступ к микрофону: `adb shell pm grant ru.tvvoice android.permission.RECORD_AUDIO`
- Тест без пульта: `adb shell input keyevent 231` (works), для физического пульта проблему смотреть по 219.
- Отладка командами без микрофона: `am start -n ru.tvvoice/.MainActivity --es tb64 base64(...)` (PowerShell портит кавычки — watch out)
- gh: Размер APK ~60 МБ (Vosk модель внутри).

## Возврат состояния ТВ (если что-то пойдёт не так)
```
adb shell pm enable com.google.android.katniss
adb shell settings put secure assistant com.google.android.katniss/com.google.android.katniss.assistance.AssistantService  # фактическое имя проверить при случае
adb shell settings delete secure voice_interaction_service
```
