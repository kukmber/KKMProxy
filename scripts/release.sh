#!/bin/bash
# Выпуск новой версии: проверка, коммит, тег и публикация через GitHub Actions.
# Запуск:  ./scripts/release.sh 1.0.2 "что изменилось"
set -eu
cd "$(dirname "$0")/.."
source scripts/common.sh

VERSION=${1:-}
MESSAGE=${2:-"Обновление компонентов"}
GRADLE=app/build.gradle.kts

if [ -z "$VERSION" ]; then
  CURRENT=$(grep -m1 'val appVersionName' "$GRADLE" | cut -d'"' -f2)
  echo "Укажите версию. Сейчас: $CURRENT"
  echo "Пример: ./scripts/release.sh ${CURRENT%.*}.$(( ${CURRENT##*.} + 1 )) \"обновил ядро mihomo\""
  exit 1
fi

if ! [[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Версия должна быть вида 1.0.2, а не «$VERSION»"
  exit 1
fi

if git rev-parse "v$VERSION" >/dev/null 2>&1; then
  echo "Тег v$VERSION уже существует. Возьмите следующий номер."
  exit 1
fi

echo "== Версия -> $VERSION"
sed -i "s|^val appVersionName = \".*\"|val appVersionName = \"$VERSION\"|" "$GRADLE"

echo "== Проверка сборки"
./gradlew :app:testDebugUnitTest assembleDebug --console=plain -q

echo "== Коммит и тег"
git add -A
git commit -q -m "$MESSAGE" -m "Версия $VERSION"
git push -q origin master
git tag -a "v$VERSION" -m "KKMProxy $VERSION"
git push -q origin "v$VERSION"

echo
echo "Готово. GitHub соберёт и опубликует релиз за несколько минут:"
echo "  сборка: https://github.com/kukmber/KKMProxy/actions"
echo "  релиз:  https://github.com/kukmber/KKMProxy/releases/latest"
echo "Приложение у друзей увидит обновление в течение суток."

# Собранные файлы кладём рядом, чтобы не искать их в папках проекта
cp app/build/outputs/apk/debug/*arm64*.apk "$KKM_OUT/" 2>/dev/null && \
  echo "Отладочная сборка скопирована в $(win_path "$KKM_OUT")"
