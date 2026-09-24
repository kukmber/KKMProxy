#!/bin/bash
# Показывает, что изменилось в оригиналах с той версии, что у нас.
# Запуск:  ./scripts/whats-new.sh
set -u
cd "$(dirname "$0")/.."

GRADLE=app/build.gradle.kts
UPDATES=app/src/main/java/io/github/romanvht/byedpi/update/Updates.kt

# GitHub иногда отвечает не с первого раза — пробуем трижды
api() {
  local url=$1 try out
  for try in 1 2 3; do
    out=$(curl -fsS -m 25 -H 'User-Agent: KKMProxy' "$url" 2>/dev/null) && [ -n "$out" ] && {
      printf '%s' "$out"
      return 0
    }
    sleep 2
  done
  return 1
}

latest_tag() {
  api "https://api.github.com/repos/$1/releases/latest" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('tag_name',''))" 2>/dev/null
}

# Тег пишут по-разному: v1.7.8 у одних, v.1.7.8 у других.
# Ищем среди настоящих тегов тот, где те же цифры, что в нашей версии.
find_tag() { # <репозиторий> <наша версия>
  api "https://api.github.com/repos/$1/tags?per_page=100" \
    | python3 -c "
import json, re, sys
want = re.findall(r'\d+', '$2')
try:
    tags = json.load(sys.stdin)
except Exception:
    raise SystemExit
for t in tags:
    if re.findall(r'\d+', t['name']) == want:
        print(t['name']); break
" 2>/dev/null
}

show() { # <репозиторий> <наша версия> <название> <что пропускать>
  local repo=$1 have=$2 title=$3 skip=$4 new have_tag
  new=$(latest_tag "$repo")

  printf '\n========== %s\n' "$title"
  if [ -z "$new" ]; then
    echo "Не удалось узнать версию: нет сети или GitHub временно не отвечает."
    echo "Посмотреть глазами: https://github.com/$repo/releases"
    return
  fi
  echo "у нас:     $have"
  echo "вышла:     $new"
  if [ "$(grep -oE '[0-9]+' <<< "$have" | tr -d '\n')" = "$(grep -oE '[0-9]+' <<< "$new" | tr -d '\n')" ]; then
    echo "Всё свежее, делать нечего."
    return
  fi

  have_tag=$(find_tag "$repo" "$have")
  have_tag=${have_tag:-$have}
  echo "смотреть:  https://github.com/$repo/compare/$have_tag...$new"
  echo
  echo "Что поменялось (без того, что нам не нужно):"
  api "https://api.github.com/repos/$repo/compare/$have_tag...$new" \
    | python3 -c "
import json, sys, re
skip = re.compile(r'''$skip''')
try:
    d = json.load(sys.stdin)
except Exception:
    print('  не удалось получить список'); raise SystemExit
print('  правок всего:', d.get('total_commits', '?'))
for c in d.get('commits', []):
    print('   -', c['commit']['message'].split(chr(10))[0][:80])

# Правки, которые почти всегда можно пропустить
noise = re.compile(r'(version|__init__|readme|changelog|\.md\$|locale|i18n)', re.I)
files = [f for f in d.get('files', []) if not skip.search(f['filename'])]
print()
print('  файлы, которые нас касаются:', len(files), 'из', len(d.get('files', [])))
for f in files:
    mark = 'мелочь' if noise.search(f['filename']) else 'смотреть'
    print('   [%s] %s (+%d -%d)' % (mark, f['filename'], f['additions'], f['deletions']))

print()
print('  ИЗМЕНЁННЫЕ СТРОКИ (- убрали, + добавили):')
for f in files:
    if noise.search(f['filename']):
        continue
    patch = f.get('patch')
    if not patch:
        continue
    print()
    print('  --- ' + f['filename'])
    lines = [l for l in patch.split(chr(10)) if l[:1] in '+- ' and not l.startswith(('+++', '---'))]
    shown = [l for l in lines if l[:1] in '+-']
    for l in shown[:30]:
        print('   ' + l[:140])
    if len(shown) > 30:
        print('   ... ещё %d строк, смотри по ссылке выше' % (len(shown) - 30))
" 2>/dev/null || echo "  не удалось получить список"
}

TGWS_HAVE=$(grep -m1 'val tgwsPortedFrom' "$GRADLE" | cut -d'"' -f2)
BBD_HAVE=$(grep -m1 'bundled = "v1' "$UPDATES" | cut -d'"' -f2)

# У tg-ws-proxy нам не нужны окно, трей, документация и сборка под Windows
show Flowseal/tg-ws-proxy "$TGWS_HAVE" "TgWsProxy (переписан у нас на Kotlin)" '^(ui/|docs/|packaging/|utils/tray)'
# У ByeByeDPI не нужны переводы на другие языки и оформление репозитория
show romanvht/ByeByeDPI "$BBD_HAVE" "ByeByeDPI (наш проект вырос из него)" '(^\.github/|^fastlane/|^README|^scripts/|/res/values-(en|tr|kk|vi)/)'

cat <<'EOF'

----------
Дальше открой ссылку «смотреть» в браузере: там видно каждую правку.
Как решать, что переносить, — в ОБНОВЛЕНИЕ.md, раздел «Как переносить изменения самому».
Не разбираешься — покажи этот вывод Claude, он разберёт.
EOF
