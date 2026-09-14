#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mode="${1:-debug}"
case "$mode" in debug|release) ;; *) echo 'Usage: scripts/build.sh [debug|release]' >&2; exit 1;; esac
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/libexec/java_home ]; then JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true); fi
  if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
    JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  fi
  export JAVA_HOME
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then ANDROID_HOME=$ANDROID_SDK_ROOT
  elif [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [ -d "$HOME/Android/Sdk" ]; then ANDROID_HOME="$HOME/Android/Sdk"
  else echo 'Set ANDROID_HOME to your Android SDK directory.' >&2; exit 1; fi
  export ANDROID_HOME
fi
if [ "$mode" = release ]; then
  : "${VITE_TLDRAW_LICENSE_KEY:?A valid tldraw license for the packaged app origin is required}"
  : "${SIGNING_STORE_FILE:?Set the absolute path to your release keystore}"
  : "${SIGNING_STORE_PASSWORD:?Set the keystore password}"
  : "${SIGNING_KEY_ALIAS:?Set the signing key alias}"
  : "${SIGNING_KEY_PASSWORD:?Set the signing key password}"
  [ -f "$SIGNING_STORE_FILE" ] || { echo 'Release keystore does not exist.' >&2; exit 1; }
fi
npm --prefix web run typecheck
if [ "$mode" = release ]; then
  NODE_ENV=production npm --prefix web run build:release
  cd android
  ./gradlew :app:assembleRelease --console=plain
else
  npm --prefix web run build
  cd android
  ./gradlew :app:assembleDebug --console=plain
fi
