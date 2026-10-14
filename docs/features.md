# vPlayer — Матрица функций для версии 0.2.0

Функциональность API сверяется с [MarshalX yandex-music-api](https://github.com/MarshalX/yandex-music-api).
Список ниже является планом v0.2.0, а не подтверждением проверки всех функций на часах.
В v0.2.1 исправлены список плейлистов и интерфейс; медиадвижок не переработан.

## Приоритет 1 — Обязательные для MVP+ (v0.2.0)

| Функция | Cassette / API | Реализация на часах | Проверка | Ограничение |
|---|---|---|---|---|
| Авторизация | OAuth Device Flow | QR-ссылка + код на экране часов | `pollDeviceToken` pending/expired/cancel | — |
| Выход из аккаунта | очистить токен | Кнопка в настройках | `logout()` | — |
| Список Liked | `GET /users/{uid}/likes/tracks` | Экран Liked с прокруткой | `likedTracks(uid)` | Требует Плюс для полного потока |
| Пользовательские плейлисты | `GET /users/{uid}/playlists/list` | Экран списка плейлистов | `playlistsList()` | Исправлено в v0.2.1 |
| Содержимое плейлиста | `GET /users/{uid}/playlists/{kind}` | Экран треков плейлиста | `playlistTracks()` | — |
| Поиск (трек) | `GET /search?text=...&type=track` | Текстовый поиск через BT-клавиатуру | `search(text, "track")` | — |
| Поиск (плейлист) | `GET /search?text=...&type=playlist` | Тот же экран, tabs | `search(text, "playlist")` | — |
| Воспроизведение выбранного | `GET /tracks/{id}/download-info` | Media3 ExoPlayer | `downloadInfo()` → `buildDirectLink()` | Нет полного потока → skip/preview |
| Очередь (локальная) | в приложении | Media3 `Queue` | `mediaPlayer.currentMediaItemIndex` | — |
| Пауза/Возобновление | — | Media3 `play()/pause()` | `isPlaying` / `isPrepared` | — |
| Next / Prev | — | Media3 `seekToNext()/seekToPrev()` | — | — |
| Seek / Перемотка | — | Кольцевой slider (rotary) | `seekTo(positionMs)` | Требует hardware rotary |
| Прогресс | — | Круговой прогресс-бар | `position` / `duration` | — |
| Like / Unlike трека | `POST /users/{uid}/likes/tracks/add-multiple` и `/remove` | Кнопка ❤ / 🤍 на плеере | `likeTrack()` / `unlikeTrack()` | — |
| Dislike (не рекомендовать) | `POST /users/{uid}/dislikes/tracks/add-multiple` | Кнопка 🚫 в списке действий | `dislikeTrack()` | — |
| Shuffle | — | Кнопка 🔀 на плеере | `shuffleModeEnabled` | — |
| Repeat | — | Кнопка 🔁 на плеере | `repeatMode` | — |
| Качество (битрейт) | выбор варианта из `download-info` | Переключатель в настройках | `quality` pref | Зависит от подписки |
| Громкость | Android `AudioManager` | Кнопки на плеере | `volume` | — |
| Bluetooth-кнопки | Android `MediaSession` | `Media3 MediaSession` | `MediaSession.Callback` | — |
| Фоновое воспроизведение | — | `MediaSessionService` + `addSession` + wake lock | исправлено в v0.3.0 | Huawei: разрешить работу в фоне |
| Круглый экран | — | BoxInsetLayout + custom-View | тест на эмуляторе round | Watch4 Pro не Wear OS SDK |

## Приоритет 2 — Опциональные (следующие версии)

| Функция | Cassette / API | Реализация | Ограничение |
|---|---|---|---|
| Моя волна (радио) | `rotor/send-radio-state` | Не реализовано в v0.2.0 | Сложный API, отдельная версия |
| Рекомендации | `landing/dashboard` | Не реализовано | Отдельная версия |
| Плейлисты по поиску (добавить в Liked) | `playlists_insert_track` | Не реализовано | Нужен diff-протокол |
| Offline-кэш | download-info → файл | **Реализовано в v0.3.0** (JSON + mp3 в files/) | Нужна подписка Плюс для полных треков |
| Голосовой поиск | Android SpeechRecognizer | Не реализовано | Может отсутствовать на Huawei |
| Подкасты | отдельные endpoints | Не реализовано | Отдельная версия |
| Альбомы исполнителя | `GET /artists/{id}/albums` | Не реализовано | — |
| Очередь синхронизации между устройствами | `POST /queues` | Не реализовано | Требует device-id |

## Что НЕ входит в «полный функционал»

- Соавторство плейлистов (`playlists_collective_join`)
- Видео-клипы (`clips` endpoint)
- Концерты (`concerts`)

## Список изменений v0.2.0

- Переход на Media3 (ExoPlayer + MediaSession + MediaSessionService)
- Полная реорганизация на ViewModel + StateFlow
- Все экраны (Login / Home / Liked / Playlist / Search / Player / Queue / Settings)
- Круглая адаптация: `BoxInsetLayout` (или эквивалент), круглые отступы, rotary-скролл
- Заголовок `X-Yandex-Music-Device` для `POST /queues` (если реализуем)
- Реализация like/dislike, shuffle, repeat, seek

## Список изменений v0.3.0

- Фоновое воспроизведение: started + foreground сервис, wake lock между треками, next/prev из уведомления и гарнитуры.
- Оффлайн: скачивание «Мне нравится» и плейлистов, раздел «Скачанное», работа без сети, принудительный оффлайн.
- Управление хранилищем: обновление/докачка, удаление плейлиста и всего, качество загрузки.

## Список изменений v0.3.1

- Пауза загрузки без удаления скачанного, продолжение с места остановки (HTTP Range), удаление с подтверждением.
