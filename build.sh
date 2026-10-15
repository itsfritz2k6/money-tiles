#!/bin/bash
# Build + sign Money Tiles. Bump versionCode in app/build.gradle first for an update.
#   ./build.sh        public build (MoneyTiles.apk) - the one that goes on GitHub. Never contains personal data.
#   ./build.sh alta   private build with her organised statements + pay setup built in (seed.js from the private
#                     folder). Lands in ~/Documents/Money Tiles Import/. NEVER upload it.
set -euo pipefail
A=~/.cache/fritz-apk; BT=$A/sdk/build-tools/36.1.0; PRIV=~/Documents/"Money Tiles Import"
SEED=~/isavemoney-app/app/src/main/assets/seed.js
set -a; . ~/.config/fritz/.env; set +a
export JAVA_HOME=$A/jdk ANDROID_HOME=$A/sdk GRADLE_USER_HOME=$A/gradle PATH=$A/jdk/bin:$PATH
cd ~/isavemoney-app
rm -f "$SEED"; trap 'rm -f "$SEED"' EXIT
OUT=MoneyTiles.apk
if [ "${1:-}" = alta ]; then cp "$PRIV/seed.js" "$SEED"; OUT="$PRIV/MoneyTiles-Alta.apk"; fi
./gradlew assembleRelease --no-daemon -q
$BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$BT/apksigner sign --ks ~/.config/fritz/isavemoney.keystore --ks-key-alias isavemoney \
  --ks-pass env:ISAVEMONEY_KEYSTORE_PASS --key-pass env:ISAVEMONEY_KEYSTORE_PASS --out "$OUT" aligned.apk
rm -f aligned.apk "$OUT.idsig"
if [ "$OUT" = MoneyTiles.apk ] && unzip -l MoneyTiles.apk | grep -q seed.js; then echo "PUBLIC APK CONTAINS seed.js - do not publish"; exit 1; fi
echo "Built $OUT"
