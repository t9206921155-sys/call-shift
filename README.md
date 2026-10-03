# CallShift — перехват и переадресация входящих вызовов (Android)

Референс-реализация milestones **M0 + M1 + M2** по техническому заданию [`docs/TZ_CallShift.md`](docs/TZ_CallShift.md) (v1.1).

**Что это:** личное Android-приложение (APK, sideload), которое перехватывает входящий вызов
через `CallScreeningService` и перенаправляет его по правилам: сетевая переадресация
оператора (MMI), отбой + дозвон на цель, уведомление/эскалация. Поддерживает **профиль A**
(`ROLE_CALL_SCREENING`) и **профиль B** (`ROLE_DIALER` с собственным dialer и InCallService экранами).
SIP-мост (S3) — заглушка до M3.

**Что это НЕ:** не публикация в Google Play, не продукт для третьих лиц. Используется на
собственном устройстве и собственных SIM. См. юридическую оговорку ниже.

---

## 1. Быстрый старт (5 минут)

```bash
# 1. Установить готовый релизный APK (подписан v3-ключом, R8, 2.9 МБ)
adb install -r -t apks/CallShift-v0.2.1-m2-standard-release.apk

# 2. Настроить роли и разрешения одной командой
bash scripts/setup_device.sh apks/CallShift-v0.2.1-m2-standard-release.apk

# 3. Открыть приложение → «Мастер настройки» → «Перепроверить»

# 4. Логи в реальном времени
adb logcat -s CallShift:D
```

Если устройство не подключено по USB — скопируйте APK на телефон и установите вручную,
затем выполните шаги из `docs/INSTALL.md` (там же — команды выдачи роли без скрипта).

---

## 2. Состав поставки

| Путь | Что это |
|---|---|
| `docs/TZ_CallShift.md` | Техническое задание (основной документ), v1.1 |
| `docs/TZ_CallShift.docx` | ТЗ в формате Word |
| `docs/TZ_CallShift.pdf` | ТЗ в формате PDF (A4, с содержанием) |
| `docs/PAMYATKA.md` | **Памятка команд**: роли, ADB, правила, MMI-коды, неполадки |
| `docs/INSTALL.md` | Пошаговая установка и настройка (adb и ручной путь) |
| `docs/OEM_NOTES.md` | Особенности Xiaomi/Samsung/Huawei/Oppo/Pixel |
| `docs/TEST_CHECKLIST.md` | Приёмочный чек-лист полевых тестов (ТЗ п. 14.3) |
| `scripts/setup_device.sh` | Выдача ролей и разрешений через ADB |
| `scripts/uninstall_cleanup.sh` | Корректное удаление: снятие MMI-переадресаций, ролей, разрешений |
| `app/` | Исходный код (Kotlin, Views + ViewBinding) |
| `apks/CallShift-v0.2.1-m2-standard-release.apk` | **Готовый подписанный APK** (2,9 МБ, R8, v3-подпись) |
| `apks/CallShift-v0.2.1-m2-standard-debug.apk` | Отладочный APK (14 МБ, без обфускации, packageId `.debug`) |
| `CHANGELOG.md` | История версий (M0/M1/M2) |

Готовые APK лежат в `apks/` — их можно сразу поставить на устройство
(`adb install -r -t apks/CallShift-v0.2.1-m2-standard-release.apk`).

---

## 3. Архитектура за 60 секунд

```
Входящий вызов (GSM/VoLTE)
         │
         ▼
[Telecom framework]
         │
         ▼  (бюджет 2800 мс, fail-open)
CallScreeningService.onScreenCall(Call.Details)
         │
         ├──► PhoneNumberNormalizer (E.164, libphonenumber, регион FI)
         │
         ├──► RuleEngine.evaluate(CallContext)
         │       ├── Проверка экстренных номеров (112, 911...) → PASS
         │       ├── Мастер-переключатель включён?
         │       ├── Фильтрация по времени/дням недели (ScheduleMatcher)
         │       ├── Фильтрация по SIM (PhoneAccountRef)
         │       └── Условия: маски (+35840*), контакты, анонимы, сеть, батарея
         │
         ├──► respondToCall(Call.Details, CallResponse)  ◄── СИСТЕМА ОСВОБОЖДЕНА
         │
         ▼  (асинхронно через ForwardDispatcher)
ForwardDispatcher.submit(Decision)
         │
         ├── Strategy S1: MMI-код (*21*..., *61*...**20#, ##002#) через TelecomPort
         ├── Strategy S2: Отбой + задержка 800 мс + дозвон на цель + DTMF исходного CLI
         ├── Strategy S4: Локальное уведомление + WorkManager retry-очередь
         └── Strategy S3: SIP-мост (заглушка NotAvailable до M3)
```

---

## 4. Что реализовано в M0 + M1 + M2

### Перехват и движок правил (M0/M1)
- `CallScreeningService` с бюджетом 2800 мс и абсолютным fail-open (любая ошибка/таймаут → `PASS`).
- Ответ платформе через актуальный метод `respondToCall(Call.Details, CallResponse)`.
- Нормализация номеров E.164 (libphonenumber 8.13.40, defaultRegion="FI").
- Движок правил: приоритеты, маски (`+35840*`, `*4567`, `+35840???????`), расписание через полночь (22:00–07:00), дни недели, фильтрация по SIM, проверка контактов, детект анонимных вызовов.
- Защита: абсолютный пропуск экстренных номеров (112, 911, 08 и др.), пропуск self-managed вызовов.
- Стратегии:
  - **S1 (MMI)**: генерация и отправка кодов `*21*`, `*61*…**20#`, `*62*`, `*67*`, `##002#`, приём USSD-ответа сети.
  - **S2 (callback-дозвон)**: отбой → пауза 800 мс → дозвон на цель, 1 ретрай, guard'ы: антипетля (`guard_loop_self`, `guard_loop_owner`, `guard_recursion`), антишторм (макс. 3 дозвона за 10 мин), cooldown 30 с, контроль премиум-диапазонов.
  - **S4 (уведомление)**: каналы `forward` и `error`, heads-up уведомления.
- Экран «Диагностика»: автогенерация adb-команд под модель/пакет + копирование в буфер.
- Журнал: история событий с таймингами (screening_ms, forwardMs, totalMs), маскирование номеров в UI/логах, экспорт в CSV через FileProvider.
- Восстановление после перезагрузки: `BOOT_COMPLETED` восстанавливает desired-state MMI на всех SIM и проверяет роли.
- Broadcast-команды `MASTER_TOGGLE` и `PANIC`, защищённые `fi.callshift.app.permission.CONTROL`.

### Профиль B и системная интеграция (M2)
- **Роль `ROLE_DIALER`**:
  - `DialerActivity`: полноценный экран набора номера (0–9, *, #, долгое нажатие '0' вводит '+', backspace, прямой вызов через `telecom.dial`), обработка `ACTION_DIAL` и `ACTION_CALL` с поддержкой схем `tel:` и `voicemail:`.
  - `CallShiftInCallService`: `InCallService` зарегистрирован с `BIND_INCALL_SERVICE` (не exported=false), связь никогда не возвращает null-binding.
  - `InCallActivity`: экран входящего и активного вызова поверх экрана блокировки (`showWhenLocked`, `turnScreenOn`), ответ/отбой, удержание, громкая связь, микрофон, вторичные вызовы (удержание/своп).
- **DTMF-передача исходного CLI (ТЗ п. 10.6)**:
  - `DtmfTransmitter`: формирование последовательности тонов для передачи исходного номера звонящего на целевую АТС/IVR после соединения.
  - Интеграция с `InCallController`: отправка тонов через `Call.playDtmfTone` при переходе вызова в статус `STATE_ACTIVE`.
- **Шторка быстрых настроек (Quick Settings Tile, FR-8.3)**:
  - `CallShiftTileService`: переключение мастер-переключателя в 1 тап из шторки Android.
- **Очередь повторной доставки (FR-6.3)**:
  - `ExternalRetryQueueImpl` + `WorkManager`: сохранение неотправленных внешних событий, backoff-повторы с ограничением очереди в 100 записей.

---

## 5. Что НЕ реализовано (следующие milestones)

| Пункт ТЗ | Статус | Почему |
|---|---|---|
| Стратегия **S3**: SIP-мост (M3) | заглушка `NotAvailable` | Нужен SIP-стек (PJSIP/linphone-sdk) и self-managed `ConnectionService` (ТЗ п. 21, источник 8) |
| Аудио-мост L2/L3 (M4) | не реализован | Требует root/system-подписи для захвата аудиопотока (ТЗ п. 10.7) |
| Миграция хранилища на Room + DataStore | JSON-файлы и SharedPreferences | Интерфейсы `RuleStore`/`SettingsPort`/`EventRecorder` изолируют хранилище — замена локальна (ТЗ п. 6.5) |
| Jetpack Compose | Views + ViewBinding | Осознанно: быстрее собирается, меньше расход памяти при сборке (ТЗ п. 8) |
| Определение SIM во время входящего | best-effort | `Call.Details.getPhoneAccountHandle()` — `@hide` (Приложение E, P-2) |
| Скрытие записи в системном журнале вызовов | невозможно | `setSkipCallLog()` игнорируется платформой для сторонних приложений (Приложение E, P-11) |

---

## 6. Сборка и подпись

| Параметр | Значение |
|---|---|
| JDK | 21 (или 17) |
| Gradle | 8.9 (wrapper) |
| AGP / Kotlin | 8.5.2 / 1.9.24 |
| `compileSdk` / `targetSdk` / `minSdk` | 35 / 35 / 28 |
| Flavors | `standard`, `root` (заготовка под профиль C) |
| Build types | `debug` (suffix `.debug`), `release` (R8 full) |

```bash
./run_build.sh :app:assembleStandardRelease      # релизный APK (~2,9 МБ, R8 + shrinkResources)
./run_build.sh :app:assembleStandardDebug        # отладочный APK (~14 МБ, без обфускации)
./run_build.sh :app:testStandardDebugUnitTest    # 26 unit-тестов домена (0 сбоев)
```

Релизный ключ:
- Keystore: `/home/user/CallShift/keys/callshift-release.jks`
- Alias: `callshift`
- Пароль: `CallShift#2026!release`
- `keystore.properties` сконфигурирован в корне проекта и добавлен в `.gitignore`.

---

## 7. Юридическое уведомление

Приложение предназначено исключительно для законного использования владельцем устройства
на принадлежащих ему SIM-картах. Перехват и переадресация вызовов третьих лиц без их согласия
могут нарушать тайну связи (ст. 138 УК РФ / ст. 63 126-ФЗ / GDPR Art. 5, 6).
Экстренные вызовы (112, 911 и др.) никогда не блокируются приложением.
