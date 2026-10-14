# vPlayer — клиент Яндекс.Музыки для Huawei Watch 4 Pro

Клиент для часов Huawei Watch 4 Pro, воспроизводящий **Мне нравится** (Liked), пользовательские плейлисты и результаты поиска из вашего аккаунта Яндекс.Музыки. Приложение — обычный Android-APK (не HarmonyOS-напи), который запускается на часах через Android-слой двухфреймворковой HarmonyOS и устанавливается через `adb install`.

Работа с API повторяет реализацию из проекта [Cassette](https://gitlab.gnome.org/Rirusha/Cassette) (GNOME) и библиотеки [yandex-music-api](https://github.com/MarshalX/yandex-music-api) (MarshalX), адаптирована под круглый экран часов.

## Возможности

### v0.3.0 (оффлайн-режим и фоновое воспроизведение)

- **Музыка не останавливается** при выключенном экране, выходе на циферблат или в другое приложение.
  Причина остановки в v0.2.x: сервис был только привязан к экрану (`bindService`) и уничтожался
  при `onStop`. Теперь сервис запускается сам (`startService`), сессия добавлена через `addSession`,
  и Media3 переводит его в foreground `mediaPlayback` с медиа-уведомлением.
- `ExoPlayer.setWakeMode(WAKE_MODE_NETWORK)` + короткий partial wake lock (до 30 с) на время
  получения ссылки следующего трека — переход между треками работает с погасшим экраном.
- Next / Prev из уведомления, Bluetooth-гарнитуры и системных медиа-кнопок часов (ForwardingPlayer).
- **Скачивание для оффлайна**: «Мне нравится» и любые плейлисты — кнопка «⬇ Скачать для оффлайна»
  на экране списка. Загрузка идёт в фоне (WorkManager, foreground `dataSync`), переживает закрытие
  приложения и перезапуск процесса; прогресс «Загрузка N/M» виден на экране и в уведомлении.
- Хранение: `files/offline/library.json` (метаданные плейлистов и треков, атомарная запись)
  и `files/offline/<id>.mp3`. Приватная память приложения, разрешения на хранилище не нужны.
  Недокачанные `.part` удаляются; файл появляется только после проверки размера.
- «Обновить» докачивает новые треки и удаляет файлы, убранные из плейлиста (если они не нужны другому).
- Воспроизведение: скачанный трек всегда играет из файла, даже при наличии сети.
  Без сети нескачанные треки пропускаются автоматически.
- Без сети приложение открывается по сохранённому аккаунту; на главном экране значок
  «Оффлайн», раздел **«Скачанное»** работает в авиарежиме.
- Настройки: принудительный оффлайн-режим, качество загрузки (макс. до 320 / эконом ~192 кбит/с),
  занято/свободно, «Удалить всё скачанное», кнопка «Разрешить работу в фоне».
- Проверка: [docs/v0.3.0-validation.md](docs/v0.3.0-validation.md).

### v0.2.1 (исправление плейлистов и геометрии экрана)

- Список плейлистов: исправлен запрос на `GET /users/{uid}/playlists/list`.
  Endpoint проверен по [реализации yandex-music-api](https://raw.githubusercontent.com/MarshalX/yandex-music-api/master/yandex_music/_client/playlists.py).
- При открытии используется `owner.uid`; короткие записи треков больше не пропускаются,
  `albumId` берётся из внешней записи или `albums[0].id`, а не превращается в строку `"null"`.
- Все списки, вход и настройки помещаются во вписанный в круг квадрат с запасом 6dp по радиусу.
  Размер берётся из ограничений Compose, без предположения «466 пикселей = 466dp».
- На экране плеера остаются название, исполнитель, предыдущий / пауза / следующий,
  время и кнопка «Ещё». Кольцо прогресса рисуется вокруг экрана, не занимает фиксированные 200dp.
- Перемотка, очередь, лайк, повтор, перемешивание и громкость доступны на странице «Ещё».
  Это изменение разметки; медиадвижок и его ограничения из v0.2.0 не переработаны.
- Ошибка и кнопка «Повторить» видны внутри прокручиваемой безопасной области.
- Регрессионные тесты: MockWebServer для HTTP-запросов и Robolectric / Compose для геометрии.
  Результаты и снимки: [docs/v0.2.1-validation.md](docs/v0.2.1-validation.md).

### v0.2.0 (полный функционал + адаптация под круглый экран)

- **OAuth Device Flow авторизация** — на экране часов показывается ссылка `https://ya.ru/device` и код, вы подтверждаете вход с телефона/ПК.
- **Резервный ввод токена вручную** — если Device Flow недоступен.
- **Список Liked-треков** — загружается по `users/{uid}/likes/tracks`, метаданные батчами по 100.
- **Пользовательские плейлисты** — исправлены в v0.2.1 на `users/{uid}/playlists/list` + содержимое каждого.
- **Поиск треков** — `search?text=...&type=track`; в v0.2.1 запуск по кнопке «Найти».
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

Версия `0.3.0` — оффлайн-режим и фоновое воспроизведение.
Регрессионные тесты используют синтетические данные, не аккаунт пользователя.
Проверка авторизованных запросов к Яндексу и новой сборки на реальных часах остаётся за пользователем.

## Требования

- Активная подписка Яндекс.Музыка Плюс на аккаунте — без неё API возвращает только короткие `preview`-фрагменты, приложение честно сообщит об ошибке.
- Huawei Watch 4 Pro с включённым `Developer Mode` (adb) — смотри [Build.md](Build.md).
- Токен OAuth с устройства (телефон) на котором есть вход в Яндекс-аккаунт.

## Установка через adb

```bash
adb install -r vplayer-0.3.0.apk
```

### Фон на Huawei (рекомендуется)

Прошивки Huawei агрессивно ограничивают фоновые приложения. Разрешите vPlayer работу в фоне
кнопкой в настройках приложения или через adb:

```bash
adb shell dumpsys deviceidle whitelist +com.victormuha.vplayer
adb shell cmd appops set com.victormuha.vplayer RUN_ANY_IN_BACKGROUND allow
# проверка, что сервис жив после выключения экрана
adb shell dumpsys activity services com.victormuha.vplayer | grep -E "PlaybackService|isForeground"
```

Если в настройках часов есть «Запуск приложений» — переключите vPlayer на ручное управление
и включите «Работа в фоне».

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
│   │   ├── VPlayerApp.kt             # инициализация токена, настроек, сети, хранилища
│   │   ├── api/
│   │   │   ├── ApiClient.kt          # OAuth Device Flow + Yandex Music API
│   │   │   └── Api.kt                # suspend-обёртки, выбор битрейта
│   │   ├── offline/                  # OfflineStore, TrackDownloader, OfflineSync, OfflineSyncWorker
│   │   ├── settings/AppSettings.kt   # оффлайн-флаг, качество, NetworkMonitor
│   │   ├── player/service/
│   │   │   ├── PlaybackService.kt     # Media3 MediaSessionService + ExoPlayer (foreground)
│   │   │   └── QueueLogic.kt          # выбор источника, переходы, пропуск нескачанных
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

## Проверка исправлений

```bash
./gradlew :app:testDebugUnitTest
```

Отчёт: `app/build/reports/tests/testDebugUnitTest/index.html`.
Robolectric-снимки экрана: `app/build/reports/round-layout/`.
Проверяются 466×466dp при mdpi, 233×233dp при mdpi и 466×466px при 320dpi (233×233dp).
Тесты проверяют реальные bounds кнопок, прокрутку последнего элемента и видимость кольца;
они не эмулируют прошивку Huawei.

Если ошибка HTTP повторится:

```bash
adb logcat -c
# открыть «Плейлисты» и нажать «Обновить»
adb logcat -d -s ApiClient:W
adb shell wm size
adb shell wm density
```

Приложение не пишет OAuth-токен или заголовок Authorization в эти диагностические сообщения.
В настройках отображаются размер окна в dp и плотность.

## Лицензия

MIT — см. [LICENSE](LICENSE).
