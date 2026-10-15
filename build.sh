#!/bin/bash
# Build + sign iSaveMoney. Bump versionCode in app/build.gradle first for an update.
set -euo pipefail
A=~/.cache/fritz-apk; BT=$A/sdk/build-tools/36.1.0
set -a; . ~/.config/fritz/.env; set +a
export JAVA_HOME=$A/jdk ANDROID_HOME=$A/sdk GRADLE_USER_HOME=$A/gradle PATH=$A/jdk/bin:$PATH
cd ~/isavemoney-app
./gradlew assembleRelease --no-daemon -q
$BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$BT/apksigner sign --ks ~/.config/fritz/isavemoney.keystore --ks-key-alias isavemoney \
  --ks-pass env:ISAVEMONEY_KEYSTORE_PASS --key-pass env:ISAVEMONEY_KEYSTORE_PASS --out iSaveMoney.apk aligned.apk
rm -f aligned.apk iSaveMoney.apk.idsig
echo "Built ~/isavemoney-app/iSaveMoney.apk"
