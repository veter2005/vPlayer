# vPlayer — клиент Яндекс.Музыки для Huawei Watch 4 Pro

Клиент для часов Huawei Watch 4 Pro, воспроизводящий **Мне нравится** (Liked), пользовательские плейлисты и результаты поиска из вашего аккаунта Яндекс.Музыки. Приложение — обычный Android-APK (не HarmonyOS-напи), который запускается на часах через Android-слой двухфреймворковой HarmonyOS и устанавливается через `adb install`.

Работа с API повторяет реализацию из проекта [Cassette](https://gitlab.gnome.org/Rirusha/Cassette) (GNOME) и библиотеки [yandex-music-api](https://github.com/MarshalX/yandex-music-api) (MarshalX), адаптирована под круглый экран часов.

## Возможности

### v0.2.0 (полный функционал + адаптация под круглый экран)

- **OAuth Device Flow авторизация** — на экране часов показывается ссылка `https://ya.ru/device` и код, вы подтверждаете вход с телефона/ПК.
- **Резервный ввод токена вручную** — если Device Flow недоступен.
- **Список Liked-треков** — загружается по `users/{uid}/likes/tracks`, метаданные батчами по 100.
- **Пользовательские плейлисты** — `users/{uid}/playlists` + содержимое каждого.
- **Поиск треков** — `search?text=...&type=track`, мгновенные результаты.
- **Стриминговое воспроизведение** mp3 по прямой ссылке (`/get-mp3/...`), с вычислением подписи `MD5(salt + path[1:] + s)`.
- **Media3 ExoPlayer + MediaSession** — правильная модель состояний (Idle / Loading / Ready / Error), Bluetooth-кнопки, системные уведомления.
- **Полный плеер**:
  - ⏮ / ⏸▶ / ⏭ (Next / Prev / Play-Pause)
  - Круговой прогресс-бар с текущей позицией и длительностью
  - Shuffle (🔀) и Repeat (🔁 / 🔂 / off)
  - Like / Unlike (❤ / 🤍) с оптимистичным обновлением UI
  - Громкость (шаг ±10%)
- **Очередь** — просмотр и выбор трека из очереди.
- **Фоновый сервис (mediaPlayback)** — музыка не прерывается при выключенном экране.
- **Круглая адаптация** — все экраны используют круглые отступы (safe-area), списки прокручиваются с верхним/нижним inset для видимости крайних элементов.

### v0.1.0 (первая тестовая сборка)

- OAuth Device Flow + ручной токен
- Liked-список
- Стриминг mp3 (MediaPlayer, без Media3)

## Статус

Версия `0.2.0` — вторая тестовая сборка. Проверено подписью APK (v2), не проверено на реальных часах: возможны правки отступов под круглый экран и размеров шрифтов.

## Требования

- Активная подписка Яндекс.Музыка Плюс на аккаунте — без неё API возвращает только короткие `preview`-фрагменты, приложение честно сообщит об ошибке.
- Huawei Watch 4 Pro с включённым `Developer Mode` (adb) — смотри [Build.md](Build.md).
- Токен OAuth с устройства (телефон) на котором есть вход в Яндекс-аккаунт.

## Установка через adb

```bash
adb install -r vplayer-0.2.0.apk
```

Приложение появится в списке как `vplayer`. Обновление с v0.1.0 сохранит авторизацию (тот же keystore, тот же `applicationId`).

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
│   │   ├── MainActivity.kt           # роутинг Login/Home (Compose)
│   │   ├── api/
│   │   │   └── ApiClient.kt          # OAuth Device Flow + Yandex Music API
│   │   ├── player/service/
│   │   │   └── PlaybackService.kt     # Media3 MediaSessionService + ExoPlayer
│   │   ├── ui/
│   │   │   ├── components/
│   │   │   │   └── RoundScreen.kt    # круглая адаптация + прогресс-бар
│   │   │   ├── screens/
│   │   │   │   └── Screens.kt        # все экраны (Login/Home/Liked/Playlists/Player/Queue/Settings)
│   │   │   ├── theme/
│   │   │   │   └── Theme.kt         # Material3 тема
│   │   │   └── viewmodels/
│   │   │       ├── MainViewModel.kt   # навигация + API-состояние
│   │   │       └── PlaybackViewModel.kt # обёртка PlaybackService
│   ├── AndroidManifest.xml
│   └── res/
├── build.gradle.kts    # AGP 8.5.2 + Kotlin 1.9.24 + Compose + Media3
├── settings.gradle.kts
└── gradle/wrapper/    # стандартная обёртка Gradle 8.7
```

## Технические детали

- `minSdk 26`, `targetSdk 34` (совместимо с Android-слоем Huawei Watch 4 Pro).
- **Media3 1.3.1**: `ExoPlayer`, `MediaSessionService`, `MediaSession` — правильная модель состояний, Bluetooth-кнопки, системные уведомления.
- **Compose UI**: Material3, тема с жёлтым акцентом (#FFCC00).
- OkHttp для всех HTTP-запросов.
- Заголовок `X-Yandex-Music-Client: YandexMusicAndroid/24023621` обязателен для `POST /tracks` (без него API отвечает 451).
- `POST /tracks` отправляется как `application/x-www-form-urlencoded` с повторяющимися полями `track-ids`.
- `Authorization: OAuth <token>` передаётся на все запросы `api.music.yandex.net`.
- Выбор варианта: `download-info` → mp3 с максимальным битрейтом без `preview`/`direct` флагов → `MD5(salt + path[1:] + s)` → `https://host/get-mp3/sign/ts/path`.

## Матрица функций

Полный список реализованных и запланированных функций — в [docs/features.md](docs/features.md).

## Лицензия

MIT — см. [LICENSE](LICENSE).
