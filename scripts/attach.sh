#!/bin/sh
set -eu
# adb selects the only connected device unless ANDROID_SERIAL is provided.
pid=$(adb shell pidof -s com.facundopri.tldrawink | tr -d '\r')
[ -n "$pid" ] || { echo 'Launch the debug app first.' >&2; exit 1; }
adb forward tcp:9223 "localabstract:webview_devtools_remote_$pid"
