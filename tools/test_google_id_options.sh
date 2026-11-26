#!/usr/bin/env bash
# Exercise the debug APK on the selected device without installing or opening an app.
set -euo pipefail
if [[ $# -ne 4 ]]; then
    echo "Usage: $0 ADB_SERIAL DEBUG_COMPANION.apk ANDROID_PLATFORM.jar D8" >&2
    exit 2
fi
serial=$1
apk=$2
android_jar=$3
d8=$4
[[ -r "$apk" && -r "$android_jar" && -x "$d8" ]]
src=$(cd -- "$(dirname -- "$0")" && pwd)
work=$(mktemp -d)
remote_dir=
cleanup() {
    if [[ -n "$remote_dir" ]]; then
        adb -s "$serial" shell rm -f "$remote_dir/checks.jar" "$remote_dir/companion.apk" || true
        adb -s "$serial" shell rmdir "$remote_dir" || true
    fi
    rm -rf -- "$work"
}
trap cleanup EXIT
javac --release 11 -cp "$android_jar" -d "$work" "$src/TestGoogleIdOptions.java" "$src/TestSignInRequests.java"
"$d8" --lib "$android_jar" --min-api 34 --output "$work/checks.jar" "$work"/*.class
created=$(adb -s "$serial" shell mktemp -d /data/local/tmp/microg-google-id-XXXXXXXX | tr -d '\r')
[[ "$created" =~ ^/data/local/tmp/microg-google-id-[A-Za-z0-9]+$ ]]
remote_dir=$created
adb -s "$serial" push "$work/checks.jar" "$remote_dir/checks.jar" >/dev/null
adb -s "$serial" push "$apk" "$remote_dir/companion.apk" >/dev/null
adb -s "$serial" shell chmod 400 "$remote_dir/checks.jar" "$remote_dir/companion.apk"
adb -s "$serial" shell "CLASSPATH=$remote_dir/checks.jar:$remote_dir/companion.apk app_process /system/bin TestGoogleIdOptions"
adb -s "$serial" shell "CLASSPATH=$remote_dir/checks.jar:$remote_dir/companion.apk app_process /system/bin TestSignInRequests"
