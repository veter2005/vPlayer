# Сборка vPlayer

## Окружение

- JDK 17 или выше (проверено на OpenJDK 21)
- Android SDK Command-line tools + `platforms;android-34` + `build-tools;34.0.0`
- Gradle 8.7 (можно использовать `gradle wrapper` для генерации локальной обёртки)
- Android Gradle Plugin (AGP) 8.5.2, Kotlin 1.9.24

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

Проверить подпись:

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose app-release.apk
# должно вывести: Verified using v2 scheme (APK Signature Scheme v2): true
```

## Шаг 5. Установить на Huawei Watch 4 Pro

1. Включите режим разработчика на часах: **Настройки → Система → О часах** — тап 7 раз по «Номер сборки», затем **Настройки → Система → Режим разработчика → Отладка ADB → Включить**.
2. Подключите зарядную базу к ПК через USB и положите на неё часы (или подключите по Wi-Fi adb).
3. Проверьте, что устройство видно:

   ```bash
   adb devices
   ```

4. Установите:

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

## Генерация локального Gradle wrapper

Проект не содержит `gradle/wrapper/` и `gradle-wrapper.jar` (выхолощены в .gitignore). Если вы хотите использовать стандартную Gradle-обёртку:

```bash
gradle wrapper --gradle-version 8.7
# создаёт gradle/wrapper/, gradlew, gradle.bat
```

Локальный `gradlew` в репозитории — bash-скрипт, вызывающий глобальный `gradle --no-daemon`. Если глобальный `gradle` недоступен, сгенерируйте полный wrapper (см. выше).

## Отладка

Полезные команды:

```bash
adb logcat -s vplayer:V ApiClient:V PlaybackService:V
adb shell am force-stop com.victormuha.vplayer
adb uninstall com.victormuha.vplayer
```

Тег логов: `ApiClient`, `PlaybackService`.
