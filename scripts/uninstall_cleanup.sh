#!/usr/bin/env bash
# ============================================================================
#  CallShift — корректное удаление и откат (ТЗ E-16, п. 15.3)
#
#  ВАЖНО: сетевая переадресация (MMI) остаётся включённой у оператора даже
#  после удаления приложения. Поэтому скрипт сначала отправляет коды снятия,
#  потом снимает роли и только затем удаляет приложение.
# ============================================================================
set -uo pipefail

PKG="${PKG:-fi.callshift.app}"
USER_ID="${USER_ID:-0}"

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }
info()  { printf '\033[36m%s\033[0m\n' "$1"; }

command -v adb >/dev/null 2>&1 || { red "adb не найден в PATH"; exit 1; }
adb get-state >/dev/null 2>&1 || { red "Устройство не подключено"; exit 1; }

info "=== 1. Снятие сетевой переадресации (MMI) на всех SIM ==="
# Получаем список SIM-аккаунтов, чтобы отправить коды на каждую SIM.
ACCOUNTS=$(adb shell cmd telecom list-phone-accounts 2>/dev/null | tr ',' '\n' | sed 's/^\s*//' | grep -v '^$' || true)
if [[ -z "$ACCOUNTS" ]]; then
  info "  Список SIM недоступен — отправляем коды на активную SIM"
  ACCOUNTS="(default)"
fi

CODES=("##21#" "##61#" "##62#" "##67#" "##002#" "##004#")
for acc in $ACCOUNTS; do
  for code in "${CODES[@]}"; do
    info "  $acc → $code"
    # Отправляем через штатный dialer-интент: это работает даже после снятия ролей.
    adb shell am start -a android.intent.action.CALL -d "tel:${code}" >/dev/null 2>&1
    sleep 2
  done
done
echo

info "=== 2. Снятие системных ролей ==="
adb shell cmd role remove-role-holder --user "$USER_ID" android.app.role.CALL_SCREENING "$PKG" >/dev/null 2>&1 \
  && green "  ✓ CALL_SCREENING снята" || red "  ✗ CALL_SCREENING"
adb shell cmd role remove-role-holder --user "$USER_ID" android.app.role.DIALER "$PKG" >/dev/null 2>&1 \
  && green "  ✓ DIALER снята" || info "  — DIALER не была назначена"
echo

info "=== 3. Отзыв разрешений ==="
for p in READ_PHONE_STATE READ_PHONE_NUMBERS CALL_PHONE ANSWER_PHONE_CALLS \
         READ_CALL_LOG WRITE_CALL_LOG READ_CONTACTS POST_NOTIFICATIONS; do
  adb shell pm revoke "$PKG" "android.permission.$p" >/dev/null 2>&1 && green "  ✓ отозвано $p"
done
echo

info "=== 4. Удаление из списка deviceidle ==="
adb shell dumpsys deviceidle whitelist -"$PKG" >/dev/null 2>&1 && green "  ✓ удалено из whitelist"
echo

read -r -p "Удалить приложение $PKG? [y/N] " answer
if [[ "${answer,,}" == "y" ]]; then
  adb uninstall "$PKG" >/dev/null 2>&1 && green "Приложение удалено" || red "Не удалось удалить"
else
  info "Приложение оставлено на устройстве."
fi

echo
green "=== Готово ==="
info "Проверьте, что переадресация у оператора действительно снята:"
info "  наберите *#21# и *#62# в штатном телефоне и убедитесь, что переадресация выключена."
info "Если системный телефон был заменён (роль DIALER) — выберите прежний dialer:"
info "  Настройки → Приложения → Приложения по умолчанию → Телефон."
