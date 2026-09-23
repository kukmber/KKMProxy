#!/bin/bash
# Ставит собранную debug-сборку на телефон и показывает состояние.
# Запуск:  ./scripts/install-phone.sh
set -u
cd "$(dirname "$0")/.."

ADB=$(command -v adb || echo "/mnt/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe")
[ -x "$ADB" ] || ADB="/mnt/c/Users/rober/AppData/Local/Android/Sdk/platform-tools/adb.exe"
PKG=io.github.romanvht.byedpi

if [ ! -x "$ADB" ] && ! command -v adb >/dev/null; then
  echo "adb не найден. Укажите путь: ADB=/путь/к/adb ./scripts/install-phone.sh"
  exit 1
fi

DEVICE=$("$ADB" devices | awk '/\tdevice$/ {print $1; exit}')
if [ -z "$DEVICE" ]; then
  echo "Телефон не подключён."
  echo "Включите на телефоне «Отладка по Wi-Fi» и выполните:"
  echo "  $ADB pair <адрес:порт> <код>     # один раз"
  echo "  $ADB connect <адрес:порт>"
  exit 1
fi
echo "устройство: $DEVICE"

APK=$(ls -t app/build/outputs/apk/debug/*arm64*.apk 2>/dev/null | head -1)
if [ -z "$APK" ]; then
  echo "Сборки нет. Сначала: ./gradlew assembleDebug"
  exit 1
fi
echo "ставлю: $(basename "$APK")"
"$ADB" -s "$DEVICE" install -r "$APK" || {
  echo
  echo "Если ошибка про подписи — на телефоне стоит сборка из релиза."
  echo "Удалите приложение и повторите (профили VPN сотрутся):"
  echo "  $ADB -s $DEVICE uninstall $PKG"
  exit 1
}

"$ADB" -s "$DEVICE" shell am start -n $PKG/.activities.DashboardActivity >/dev/null
echo
echo "Приложение запущено. Проверьте вручную: VPN, TgWsProxy, обход DPI."
echo
echo "Полезное:"
echo "  журнал ядра VPN:  $ADB -s $DEVICE shell run-as $PKG tail -n 50 files/mihomo/mihomo.log"
echo "  журнал сервисов:  $ADB -s $DEVICE logcat -d -s KkmVpnService:V TgWsProxyServer:V"
echo "  состояние VPN:    $ADB -s $DEVICE shell ip addr show tun0"
