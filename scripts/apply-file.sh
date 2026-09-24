#!/bin/bash
# Подставляет файл, полученный из чата, и проверяет сборку.
# Если сборка падает — откатывает и готовит новое задание с текстом ошибки.
# Запуск:  ./scripts/apply-file.sh RawWebSocket.kt
set -u
cd "$(dirname "$0")/.."

NAME=${1:-}
DOWNLOADS="/mnt/c/Users/$(whoami)/Downloads"
PROMPT="$DOWNLOADS/kkm-исправь-ошибку.txt"

if [ -z "$NAME" ]; then
  echo "Укажите имя файла, который дал чат. Пример:"
  echo "  ./scripts/apply-file.sh RawWebSocket.kt"
  echo
  echo "Файл должен лежать в папке «Загрузки»."
  exit 1
fi

NAME=$(basename "$NAME")
NEWFILE="$DOWNLOADS/$NAME"
[ -f "$NEWFILE" ] || NEWFILE="$NAME"
if [ ! -f "$NEWFILE" ]; then
  echo "Не нашёл файл «$NAME» ни в Загрузках, ни в текущей папке."
  exit 1
fi

TARGET=$(find app/src/main -name "$NAME" -type f | head -1)
if [ -z "$TARGET" ]; then
  echo "В проекте нет файла с таким именем: $NAME"
  exit 1
fi

if ! git diff --quiet -- "$TARGET"; then
  echo "Внимание: в «$TARGET» уже есть несохранённые правки."
  echo "Они будут заменены. Прервать — Ctrl+C, продолжить — Enter."
  read -r _
fi

echo "Подставляю: $NEWFILE -> $TARGET"
cp "$TARGET" "/tmp/$(basename "$TARGET").было" 2>/dev/null
cp "$NEWFILE" "$TARGET"

echo "Проверяю сборку, это займёт пару минут..."
LOG=$(mktemp)
if ./gradlew :app:testDebugUnitTest assembleDebug --console=plain > "$LOG" 2>&1; then
  echo
  echo "✔ Собралось и тесты прошли."
  echo
  echo "Дальше:"
  echo "  1) ./scripts/install-phone.sh   — поставить на телефон и проверить"
  echo "  2) если всё работает: поменяйте версию в app/build.gradle.kts"
  echo "     (строка tgwsPortedFrom) и выпустите релиз:"
  echo "     ./scripts/release.sh <версия> \"перенёс правки TgWsProxy\""
  echo
  echo "Не понравилось — вернуть как было: git checkout -- $TARGET"
  rm -f "$LOG"
  exit 0
fi

echo
echo "✘ Сборка упала. Возвращаю файл как было."
ERRORS=$(grep -E '^e: |error:' "$LOG" | head -20)
git checkout -- "$TARGET"

{
  cat <<EOF
Твой предыдущий вариант не собрался. Вот ошибки компилятора:

$ERRORS

Исправь их и пришли файл целиком ещё раз.
Напоминаю: это Android-приложение на Kotlin, файл называется $NAME.
Ниже — текущее (рабочее) содержимое файла, от него и отталкивайся.

--- файл $NAME
EOF
  cat "$TARGET"
} > "$PROMPT"

echo
echo "Ошибки:"
echo "$ERRORS" | head -8
echo
WIN_PATH=$(echo "$PROMPT" | sed 's|/mnt/c/|C:\\|; s|/|\\|g')
echo "Готово новое задание для чата: $WIN_PATH"
echo "Приложите его в тот же чат — там уже есть контекст."
rm -f "$LOG"
exit 1
