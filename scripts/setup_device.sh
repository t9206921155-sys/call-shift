#!/usr/bin/env bash
# ============================================================================
#  CallShift — первичная настройка устройства через ADB
#  Соответствует ТЗ п. 7.2 (профиль B — максимум возможностей).
#
#  Использование:
#     bash scripts/setup_device.sh [путь_к_apk]
#
#  Требования: adb в PATH, устройство с включённой отладкой по USB,
#              одна активная сессия `adb devices`.
# ============================================================================
set -uo pipefail

PKG="${PKG:-fi.callshift.app}"
APK="${1:-}"
USER_ID="${USER_ID:-0}"

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }
info()  { printf '\033[36m%s\033[0m\n' "$1"; }

command -v adb >/dev/null 2>&1 || { red "adb не найден в PATH"; exit 1; }
adb get-state >/dev/null 2>&1 || { red "Устройство не подключено (adb get-state)"; exit 1; }

info "=== 0. Устройство ==="
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
echo

# --- 1. Установка APK -------------------------------------------------------
if [[ -n "$APK" && -f "$APK" ]]; then
  info "=== 1. Установка $APK ==="
  adb install -r -t -g "$APK" && green "APK установлен" || red "Не удалось установить APK"
else
  info "=== 1. APK не передан — пропускаем установку ==="
  info "    Запустите: bash scripts/setup_device.sh app/build/outputs/apk/standard/debug/app-standard-debug.apk"
fi
echo

# --- 2. Runtime-разрешения --------------------------------------------------
info "=== 2. Runtime-разрешения (ТЗ п. 7.1) ==="
PERMISSIONS=(
  android.permission.READ_PHONE_STATE
  android.permission.READ_PHONE_NUMBERS
  android.permission.CALL_PHONE
  android.permission.ANSWER_PHONE_CALLS
  android.permission.READ_CALL_LOG
  android.permission.SEND_SMS
  android.permission.WRITE_CALL_LOG
  android.permission.READ_CONTACTS
  android.permission.POST_NOTIFICATIONS
)
for p in "${PERMISSIONS[@]}"; do
  if adb shell pm grant "$PKG" "$p" >/dev/null 2>&1; then
    green "  ✓ $p"
  else
    red   "  ✗ $p (не выдаётся на этом устройстве/версии — см. ТЗ п. 7.1)"
  fi
done
echo

# --- 3. Роль screening-приложения (перехват входящих) -----------------------
info "=== 3. Роль CALL_SCREENING (обязательно) ==="
if adb shell cmd role add-role-holder --user "$USER_ID" android.app.role.CALL_SCREENING "$PKG" >/dev/null 2>&1; then
  green "  ✓ роль назначена"
else
  red "  ✗ не удалось назначить роль через adb"
  info "    Ручной путь: Настройки → Приложения → Приложения по умолчанию →"
  info "    «Определение номера и спам-фильтр» (Caller ID & spam) → CallShift"
fi
echo

# --- 4. Роль dialer (профиль B, опционально) --------------------------------
info "=== 4. Роль DIALER (профиль B — ТЗ FR-P3, реализовано в M2) ==="
if [[ "${SET_DIALER:-no}" == "yes" ]]; then
  if adb shell cmd role add-role-holder --user "$USER_ID" android.app.role.DIALER "$PKG" >/dev/null 2>&1; then
    green "  ✓ роль DIALER назначена (в M2 реализованы DialerActivity и InCallActivity)"
  else
    red "  ✗ не удалось назначить роль DIALER"
  fi
else
  info "  пропущено (запустите с SET_DIALER=yes, чтобы назначить CallShift основным телефоном)"
fi
echo

# --- 5. Оптимизация батареи -------------------------------------------------
info "=== 5. Исключение из оптимизации батареи (ТЗ п. 11.1) ==="
adb shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 && green "  ✓ deviceidle whitelist" || red "  ✗ deviceidle"
echo

# --- 6. OEM-автозапуск (вручную) --------------------------------------------
info "=== 6. OEM-автозапуск — выполняется ВРУЧНУЮ (ТЗ п. 11.2) ==="
cat <<'OEM'
  Xiaomi / MIUI / HyperOS : Настройки → Приложения → CallShift → Автозапуск = ВКЛ,
                            Контроль активности → Нет ограничений.
                            Отключите системный «Блокировщик вызовов», если конфликтует.
  Samsung / One UI        : Настройки → Батарея → CallShift → Не отслеживать.
  Huawei / EMUI           : Батарея → Запуск приложений → CallShift → Вручную (все 3 пункта).
  Oppo/Realme/OnePlus     : Phone Manager → App management → Allow autostart.
OEM
echo

# --- 7. Проверка ------------------------------------------------------------
info "=== 7. Проверка результата ==="
echo "Держатели роли CALL_SCREENING:"
adb shell cmd role get-role-holders --user "$USER_ID" android.app.role.CALL_SCREENING
echo
echo "Держатели роли DIALER:"
adb shell cmd role get-role-holders --user "$USER_ID" android.app.role.DIALER
echo
echo "Выданные runtime-разрешения:"
adb shell dumpsys package "$PKG" | grep -A 20 "runtime permissions" | head -25
echo

green "=== Готово ==="
info "Дальше: откройте приложение → «Мастер настройки» → «Перепроверить»,"
info "затем позвоните на устройство с другого номера и посмотрите «Журнал событий»."
info "Логи: adb logcat -s CallShift:D"
info
info "Откат всего: bash scripts/uninstall_cleanup.sh"
