#!/bin/bash
# Обновляет встроенные компоненты до последних версий оригиналов.
# Запуск:  ./scripts/update-cores.sh
set -u
cd "$(dirname "$0")/.."

GRADLE=app/build.gradle.kts
CHANGED=0
MANUAL=""

say()  { printf '%s\n' "$*"; }
head2() { printf '\n== %s\n' "$*"; }

# Последний релиз репозитория на GitHub
latest_tag() {
  curl -fsS -m 25 "https://api.github.com/repos/$1/releases/latest" 2>/dev/null \
    | grep -m1 '"tag_name"' | cut -d'"' -f4
}

# Голые цифры версии для сравнения: v0.17.3 -> 0 17 3
nums() { grep -oE '[0-9]+' <<< "$1" | tr '\n' ' '; }

newer() { # newer <кандидат> <текущая>  -> 0 если кандидат новее
  local a b i x y
  read -ra a <<< "$(nums "$1")"
  read -ra b <<< "$(nums "$2")"
  for ((i = 0; i < ${#a[@]} || i < ${#b[@]}; i++)); do
    x=${a[i]:-0}; y=${b[i]:-0}
    ((x > y)) && return 0
    ((x < y)) && return 1
  done
  return 1
}

# Подмодуль переводится на последний тег-релиз
update_submodule() { # <путь> <репозиторий> <название>
  local path=$1 repo=$2 title=$3 current target
  current=$(git -C "$path" describe --tags --always 2>/dev/null)
  git -C "$path" fetch --tags --quiet origin 2>/dev/null
  target=$(latest_tag "$repo")
  [ -z "$target" ] && target=$(git -C "$path" tag --sort=-v:refname | head -1)
  if [ -z "$target" ]; then
    say "$title: не удалось узнать версию (нет сети?)"
    return
  fi
  if [ "$current" = "$target" ]; then
    say "$title: $current — уже последняя"
    return
  fi
  # Бывает, что подмодуль стоит на коммите новее тега — тогда откатывать нельзя
  if git -C "$path" merge-base --is-ancestor "$target" HEAD 2>/dev/null; then
    say "$title: $current — новее релиза $target, оставляем"
    return
  fi
  if git -C "$path" checkout --quiet "$target" 2>/dev/null; then
    # У подмодулей бывают свои подмодули (например, lwip внутри hev) — без них не соберётся
    git -C "$path" submodule update --init --recursive --quiet 2>/dev/null
    git add "$path"
    say "$title: $current -> $target ✔"
    CHANGED=1
  else
    say "$title: не удалось переключиться на $target"
  fi
}

head2 "Ядро ByeDPI"
update_submodule app/src/main/cpp/byedpi hufrea/byedpi "ByeDPI"

head2 "hev-socks5-tunnel"
update_submodule app/src/main/jni/hev-socks5-tunnel heiher/hev-socks5-tunnel "hev-socks5-tunnel"

head2 "Ядро VPN (mihomo)"
MIHOMO_NOW=$(grep -m1 'val mihomoVersion' "$GRADLE" | cut -d'"' -f2)
MIHOMO_NEW=$(latest_tag MetaCubeX/mihomo)
if [ -z "$MIHOMO_NEW" ]; then
  say "mihomo: не удалось узнать версию (нет сети?)"
elif [ "$MIHOMO_NOW" = "$MIHOMO_NEW" ]; then
  say "mihomo: $MIHOMO_NOW — уже последняя"
else
  sed -i "s|val mihomoVersion = \"$MIHOMO_NOW\"|val mihomoVersion = \"$MIHOMO_NEW\"|" "$GRADLE"
  say "mihomo: $MIHOMO_NOW -> $MIHOMO_NEW ✔"
  CHANGED=1
fi

head2 "Требуют ручной работы"
TGWS_NOW=$(grep -m1 'val tgwsPortedFrom' "$GRADLE" | cut -d'"' -f2)
TGWS_NEW=$(latest_tag Flowseal/tg-ws-proxy)
if [ -n "$TGWS_NEW" ] && newer "$TGWS_NEW" "$TGWS_NOW"; then
  say "TgWsProxy: у нас порт $TGWS_NOW, в оригинале $TGWS_NEW"
  say "   изменения: https://github.com/Flowseal/tg-ws-proxy/releases"
  MANUAL="$MANUAL TgWsProxy"
else
  say "TgWsProxy: $TGWS_NOW — соответствует оригиналу"
fi

BBD_NEW=$(latest_tag romanvht/ByeByeDPI)
BBD_NOW=$(grep -m1 'bundled = "v1' app/src/main/java/io/github/romanvht/byedpi/update/Updates.kt | cut -d'"' -f2)
if [ -n "$BBD_NEW" ] && newer "$BBD_NEW" "$BBD_NOW"; then
  say "ByeByeDPI: у нас $BBD_NOW, вышла $BBD_NEW"
  say "   слить: git fetch upstream && git merge upstream/master   (будут конфликты)"
  MANUAL="$MANUAL ByeByeDPI"
else
  say "ByeByeDPI: $BBD_NOW — соответствует оригиналу"
fi

head2 "Итог"
if [ "$CHANGED" = 1 ]; then
  say "Что-то обновилось. Дальше:"
  say "  1) ./gradlew :app:testDebugUnitTest assembleDebug   — проверить сборку"
  say "  2) поставить на телефон и проверить подключение"
  say "  3) ./scripts/release.sh 1.0.2                       — выпустить релиз"
else
  say "Всё уже свежее, делать нечего."
fi
[ -n "$MANUAL" ] && say "Вручную переносить:$MANUAL (лучше вместе с Claude)"
exit 0
