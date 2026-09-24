#!/bin/bash
# Собирает готовое задание для чата с Claude: правки авторов + наши файлы.
# Запуск:  ./scripts/make-prompt.sh
set -u
cd "$(dirname "$0")/.."

OUT="/mnt/c/Users/$(whoami)/Downloads/kkm-задание-для-claude.txt"
[ -d "$(dirname "$OUT")" ] || OUT="$PWD/kkm-задание-для-claude.txt"

GRADLE=app/build.gradle.kts
SRC=app/src/main/java/io/github/romanvht/byedpi/telegram/proxy
HAVE=$(grep -m1 'val tgwsPortedFrom' "$GRADLE" | cut -d'"' -f2)

api() {
  local url=$1 try out
  for try in 1 2 3; do
    out=$(curl -fsS -m 25 -H 'User-Agent: KKMProxy' "$url" 2>/dev/null) && [ -n "$out" ] && {
      printf '%s' "$out"; return 0; }
    sleep 2
  done
  return 1
}

NEW=$(api "https://api.github.com/repos/Flowseal/tg-ws-proxy/releases/latest" \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('tag_name',''))" 2>/dev/null)

if [ -z "$NEW" ]; then
  echo "Не удалось узнать версию оригинала (нет сети?)"
  exit 1
fi
if [ "$HAVE" = "$NEW" ]; then
  echo "У нас $HAVE — это и есть последняя версия. Переносить нечего."
  exit 0
fi

echo "Собираю задание: $HAVE -> $NEW"

{
cat <<EOF
Помоги перенести изменения из чужого проекта в моё приложение.

КОНТЕКСТ
Android-приложение KKMProxy на Kotlin. В нём есть TgWsProxy — локальный
прокси для Telegram. Это наш перевод на Kotlin программы tg-ws-proxy,
которая написана на Python (https://github.com/Flowseal/tg-ws-proxy).
Наш перевод сделан с версии $HAVE, у авторов вышла $NEW.

ЧТО НУЖНО
1. Посмотри правки авторов ниже и скажи, какие из них нужно перенести,
   а какие нет. По каждой — одно предложение, почему.
2. Для нужных дай готовый код на Kotlin для моих файлов: покажи, какую
   строку на какую заменить, и объясни простыми словами, что изменится.

ПРАВИЛА ОТБОРА
- Пропускай всё, что относится к окну программы, значку в трее,
  документации, номерам версий и текстам сообщений: у нас свой
  интерфейс на Android.
- Пропускай новые аргументы командной строки: у нас их нет, настройки
  задаются в коде и в интерфейсе приложения.
- Переноси смысл, а не строки: Python и Kotlin пишутся по-разному.
- Учитывай особенности Android: например, список доверенных
  сертификатов есть в самой системе, отдельную библиотеку certifi
  подключать не нужно.
- Если правка требует новой настройки, скажи об этом отдельно — я решу,
  добавлять её в интерфейс или нет.

ПРАВКИ АВТОРОВ (- убрали, + добавили)
EOF

api "https://api.github.com/repos/Flowseal/tg-ws-proxy/compare/$HAVE...$NEW" | python3 -c "
import json, re, sys
skip = re.compile(r'^(ui/|docs/|packaging/|utils/tray)')
noise = re.compile(r'(version|__init__)', re.I)
d = json.load(sys.stdin)
for c in d.get('commits', []):
    print('*', c['commit']['message'].split(chr(10))[0])
print()
for f in d.get('files', []):
    if skip.search(f['filename']) or noise.search(f['filename']):
        continue
    if not f.get('patch'):
        continue
    print('--- файл ' + f['filename'])
    print(f['patch'])
    print()
"

echo
echo "МОИ ФАЙЛЫ (Kotlin)"
echo
for f in RawWebSocket.kt Bridge.kt WsPool.kt ProxyConfig.kt; do
  [ -f "$SRC/$f" ] || continue
  echo "--- файл $f"
  cat "$SRC/$f"
  echo
done

cat <<'EOF'
КАК ОТВЕТИТЬ
Сначала списком: что переносим и что пропускаем. Потом для каждой
нужной правки — что именно заменить в моём файле. Не переписывай файлы
целиком, покажи только изменённые места с парой строк вокруг.
EOF
} > "$OUT"

WIN_PATH=$(echo "$OUT" | sed 's|/mnt/c/|C:\\|; s|/|\\|g')
echo
echo "Готово: $WIN_PATH"
echo "Размер: $(wc -c < "$OUT") знаков, $(wc -l < "$OUT") строк"
echo
echo "Что делать:"
echo "  1) открой файл (двойной клик), выдели всё (Ctrl+A), скопируй (Ctrl+C)"
echo "  2) вставь в новый чат с Claude"
echo "  3) ответ принеси сюда — проверю перед тем, как менять код"
