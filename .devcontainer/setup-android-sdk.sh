#!/usr/bin/env bash
set -e
echo "== Устанавливаем Java 17 =="
sudo apt-get update -y
sudo apt-get install -y openjdk-17-jdk unzip wget zip
echo "== Устанавливаем Gradle через SDKMAN =="
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install gradle 8.5 < /dev/null
echo "== Скачиваем Android SDK command-line tools =="
export ANDROID_SDK_ROOT="$HOME/android-sdk"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
cd "$ANDROID_SDK_ROOT/cmdline-tools"
wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
unzip -q cmdline-tools.zip
rm cmdline-tools.zip
mv cmdline-tools latest
export PATH="$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools"
echo "== Принимаем лицензии и ставим компоненты SDK =="
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses > /dev/null
sdkmanager --sdk_root="$ANDROID_SDK_ROOT" "platform-tools" "platforms;android-34" "build-tools;34.0.0" "ndk;27.3.13750724" "cmake;3.31.4"
echo "== Сохраняем переменные окружения на будущее =="
{
  echo "export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
  echo "export ANDROID_HOME=$ANDROID_SDK_ROOT"
  echo "export PATH=\$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools"
  echo "source \$HOME/.sdkman/bin/sdkman-init.sh"
} >> "$HOME/.bashrc"
echo ""
echo "Готово!"
