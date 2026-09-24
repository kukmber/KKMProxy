#!/bin/bash
# Общие настройки скриптов. Подключается через: source scripts/common.sh

# Куда складывать файлы для тебя: задания для чата, собранные APK.
# Можно переопределить своей папкой: KKM_OUT=/путь ./scripts/…
KKM_OUT=${KKM_OUT:-"/mnt/e/Программы/KKMProxy/KKMProxy моё"}

# Если диска нет (другой компьютер) — кладём в «Загрузки»
if [ ! -d "$KKM_OUT" ]; then
  mkdir -p "$KKM_OUT" 2>/dev/null || KKM_OUT="/mnt/c/Users/$(whoami)/Downloads"
fi
[ -d "$KKM_OUT" ] || KKM_OUT="$HOME"

# Путь в виде, привычном для Windows
win_path() {
  echo "$1" | sed 's|/mnt/\([a-z]\)/|\U\1:\\|; s|/|\\|g'
}
