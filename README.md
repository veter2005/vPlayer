# vPlayer — клиент Яндекс.Музыки для Huawei Watch 4 Pro

Клиент для часов Huawei Watch 4 Pro, воспроизводящий плейлист **Мне нравится** (Liked) из вашего аккаунта Яндекс.Музыки. Приложение — обычный Android-APK (не HarmonyOS-напи), который запускается на часах через Android-слой двухфреймворковой HarmonyOS и устанавливается через `adb install`.

Работа с API повторяет реализацию из проекта [Cassette](https://gitlab.gnome.org/Rirusha/Cassette) (GNOME) и библиотеки [yandex-music-api](https://github.com/MarshalX/yandex-music-api) (MarshalX), адаптирована под экран часов.

## Возможности

- **OAuth Device Flow авторизация** — на экране часов показывается ссылка `https://ya.ru/device` и код, вы подтверждаете вход с телефона/ПК.
- **Резервный ввод токена вручную** — если Device Flow недоступен.
- **Список Liked-треков** — загружается по `users/{uid}/likes/tracks`, метаданные батчами по 100.
- **Стриминговое воспроизведение** mp3 по прямой ссылке (`/get-mp3/...`), с вычислением подписи `MD5(salt + path[1:] + s)`.
- **Фоновый сервис (mediaPlayback)** — музыка не прерывается при выключенном экране.
- **Мини-плеер** — `⏮ / ⏸▶ / ⏭`, автопереход к следующему треку.

## Статус

Версия `0.1.0` — первая тестовая сборка. Проверено подписью APK (v2), не проверено на реальных часах: возможны правки отступов под круглый экран и размеров шрифтов.

## Требования

- Активная подписка Яндекс.Музыка Плюс на аккаунте — без неё API возвращает только короткие `preview`-фрагменты, приложение честно сообщит об ошибке.
- Huawei Watch 4 Pro с включённым `Developer Mode` (adb) — смотри [Build.md](Build.md).
- Токен OAuth с устройства (телефон) на котором есть вход в Яндекс-аккаунт.

## Установка через adb

```bash
adb install -r vplayer-0.1.0.apk
```

Приложение появится в списке как `vplayer`.

## Сборка из исходников

Подробные шаги и окружение — в [Build.md](Build.md). Кратко:

```bash
# подготовить keystore (единоразово)
keytool -genkeypair -v -keystore keystore.jks -alias vplayer \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass vplayer -keypass vplayer \
  -dname "CN=vplayer, O=vplayer, C=NL"

# собрать
./gradlew :app:assembleRelease
```

Результат: `app/build/outputs/apk/release/app-release.apk`.

## Структура

```
app/
├── src/main/
│   ├── java/com/victormuha/vplayer/
│   │   ├── MainActivity.kt        # 3 экрана: Login / Liked / Player
│   │   ├── api/
│   │   │   └── ApiClient.kt       # OAuth Device Flow + Yandex Music API
│   │   └── player/
│   │       └── PlaybackService.kt  # Foreground service + MediaPlayer
│   ├── AndroidManifest.xml
│   └── res/                       # тема/иконки/строки
└── build.gradle.kts

build.gradle.kts    # AGP 8.5.2 + Kotlin 1.9.24
settings.gradle.kts
```

## Технические детали

- `minSdk 26`, `targetSdk 34` (совместимо с Android-слоем Huawei Watch 4 Pro).
- OkHttp для всех HTTP-запросов.
- Заголовок `X-Yandex-Music-Client: YandexMusicAndroid/24023621` обязателен для `POST /tracks` (без него API отвечает 451).
- `POST /tracks` отправляется как `application/x-www-form-urlencoded` с повторяющимися полями `track-ids`.
- `Authorization: OAuth <token>` передаётся на все запросы `api.music.yandex.net`.
- Выбор варианта: `download-info` → mp3 с максимальным битрейтом без `preview`/`direct` флагов → `MD5(salt + path[1:] + s)` → `https://host/get-mp3/sign/ts/path`.

## Лицензия

MIT — см. [LICENSE](LICENSE).
