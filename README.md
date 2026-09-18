<div align="center">
    <img src=".github/icon.png" alt="KKMProxy" width="150" />
    <h1>KKMProxy</h1>
    <p>VPN, прокси для Telegram и обход блокировок — в одном приложении для Android</p>
</div>

## Что умеет

**VPN** — работает на ядре [mihomo](https://github.com/MetaCubeX/mihomo), том же, что во FlClashX.

- Подписки Clash/Mihomo YAML, base64, а также ссылки `vless://` и `hysteria2://`.
- Несколько профилей: добавление по ссылке, из буфера обмена, QR-кода или файла.
- Выбор сервера с флагом страны и задержкой, переключение между VLESS и Hysteria2.
- Правила маршрутизации из подписки: российские сайты идут напрямую, остальное через VPN.

**TgWsProxy** — локальный прокси для Telegram через WebSocket, порт [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) на Kotlin. Запускается одной кнопкой, ссылка применяется в Telegram.

**byebyeDPI** — обход блокировок без VPN на ядре [ByeDPI](https://github.com/hufrea/byedpi): редактор стратегий с историей, списки доменов и автоматический подбор рабочей стратегии.

Ещё есть живые логи по каждому разделу, обновление приложения из GitHub и сезонные иконки.

## Установка

Скачайте APK со страницы [релизов](../../releases/latest):

- `KKMProxy-*-universal-release.apk` — подойдёт любому телефону;
- `KKMProxy-*-arm64-v8a-release.apk` — меньше по размеру, для телефонов последних лет.

Нужен Android 6.0 или новее. При установке разрешите установку приложений из этого источника, при первом подключении — разрешение на VPN.

## Сборка

```bash
git clone --recurse-submodules git@github.com:kukmber/KKMProxy.git
cd KKMProxy
./gradlew assembleDebug
```

Gradle сам скачает ядро mihomo. Для подписанной сборки создайте `keystore.properties` в корне:

```properties
storeFile=/путь/к/ключу.jks
storePassword=...
keyAlias=kkmproxy
keyPassword=...
```

Релизы собираются GitHub Actions по тегу вида `v1.0.1`; ключ подписи берётся из секретов `KEYSTORE_BASE64` и `KEYSTORE_PASSWORD`.

## Из чего собрано

- [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) — приложение, на основе которого сделан проект
- [ByeDPI](https://github.com/hufrea/byedpi) — ядро обхода DPI
- [mihomo](https://github.com/MetaCubeX/mihomo) — ядро VPN
- [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — оригинал прокси для Telegram
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — туннель трафика в ядро

Версии компонентов и новые версии оригиналов видно в приложении: «Настройки» → «Обновления».

## Лицензия

[Apache License 2.0](LICENSE) — как у исходного проекта ByeByeDPI.
