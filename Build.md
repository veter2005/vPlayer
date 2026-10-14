# Сборка vPlayer (v0.3.1)

## Окружение

- JDK 17 или выше (проверено на OpenJDK 21)
- Android SDK Command-line tools + `platforms;android-34` + `build-tools;34.0.0`
- Gradle 8.7 (стандартная обёртка в репозитории — `./gradlew`)
- Android Gradle Plugin (AGP) 8.5.2, Kotlin 1.9.24, Compose (1.5.14), Media3 1.3.1
- Рекомендуется `gradle.properties` с `org.gradle.jvmargs=-Xmx4g` для `material-icons-extended` dexing

## Шаг 1. Установить JDK и Android SDK

```bash
# Ubuntu/Debian
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk-headless unzip curl git

# Android SDK
mkdir -p ~/android-sdk
cd ~/android-sdk
curl -sL -o cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q cmdtools.zip && rm cmdtools.zip
mkdir -p cmdline-tools/latest
mv cmdline-tools/{bin,lib,source.properties} cmdline-tools/latest/

yes | cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1
cmdline-tools/latest/bin/sdkmanager \
  --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## Шаг 2. Настроить переменные окружения

Добавьте в `~/.bashrc` (или текущую сессию):

```bash
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
```

## Шаг 3. Подготовить keystore (единоразово)

Release-сборка подписывается собственным keystore, который не коммитится в репозиторий. Создайте его локально:

```bash
keytool -genkeypair -v \
  -keystore keystore.jks \
  -alias vplayer \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass vplayer -keypass vplayer \
  -dname "CN=vplayer, O=vplayer, C=NL"
```

Файл `keystore.jks` исключён `.gitignore`-ом.

## Шаг 4. Собрать APK

Из корня репозитория:

```bash
./gradlew :app:assembleRelease
```

Результат: `app/build/outputs/apk/release/app-release.apk`.

Регрессионные тесты API и круглого интерфейса:

```bash
./gradlew :app:testDebugUnitTest
```

Тесты не требуют OAuth-токена: используются MockWebServer и Robolectric.
Первый запуск скачивает зависимости Android для Robolectric.

Проверить подпись:

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose app-release.apk
# должно вывести: Verified using v2 scheme (APK Signature Scheme v2): true
```

## Шаг 5. Установить на Huawei Watch 4 Pro

1. Включите режим разработчика на часах: **Настройки → Система → О часах** — тап 7 раз по «Номер сборки», затем **Настройки → Система → Режим разработчика → Отладка ADB → Включить**.
2. Подключите часы через уже настроенное ADB-соединение. Способ подключения зависит от прошивки;
   возможность передачи данных через зарядную базу эта инструкция не предполагает.
3. Проверьте, что устройство видно:

   ```bash
   adb devices
   ```

4. Установите (обновит предыдущую сборку без потери авторизации, если используется тот же ключ подписи):

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

## Gradle wrapper

В репозитории уже есть стандартная обёртка Gradle 8.7 (`gradle/wrapper/`, `gradlew`, `gradlew.bat`). Скачивание Gradle произойдёт автоматически при первом запуске `./gradlew`.

## Отладка

Полезные команды:

```bash
adb logcat -s vplayer:V ApiClient:V PlaybackService:V
adb shell am force-stop com.victormuha.vplayer
adb uninstall com.victormuha.vplayer
```

Тег логов: `ApiClient`, `PlaybackService`.

Для проверки геометрии:

```bash
adb shell wm size
adb shell wm density
```

`wm size` сообщает пиксели; размеры Compose измеряются в dp и зависят от плотности.
В v0.2.1 отступы рассчитываются по текущим ограничениям окна, а размер и плотность
выведены в «Настройки».

## Изменения сборки в v0.3.0

- Новая зависимость `androidx.work:work-runtime-ktx:2.9.1` (фоновые загрузки), для тестов —
  `kotlinx-coroutines-test`.
- В манифесте: `FOREGROUND_SERVICE_DATA_SYNC`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
  класс приложения `.VPlayerApp` и переопределение `SystemForegroundService` с
  `foregroundServiceType="dataSync"` (обязательно для Android 14).
- `versionCode 4`, `versionName 0.3.0`; ключ подписи не изменился — обновление `adb install -r`
  сохраняет вход и данные.

```bash
ANDROID_HOME=$HOME/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Скачанные треки лежат в `/data/data/com.victormuha.vplayer/files/offline/`; посмотреть на
отладочной сборке можно через `adb shell run-as com.victormuha.vplayer ls -la files/offline`.

## v0.3.1

`versionCode 5`, `versionName 0.3.1`. Новых зависимостей и разрешений нет.
`.part`-файлы в `files/offline/` теперь сохраняются между запусками для докачки и удаляются
вместе с плейлистом или «Удалить всё скачанное».
