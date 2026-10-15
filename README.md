# iSaveMoney (Android)

Offline budget app in a rose-gold tile layout: Earnings (green +), Transactions (red −), a Budget
checklist to tick off, plus any extra tiles. Repeats (weekly/2-weekly/monthly/yearly, forever, N times or until a date), a calendar per tile, CSV export and JSON backup/restore.

The whole app is `app/src/main/assets/index.html`, hosted by a WebView in `MainActivity.java`.
Data lives in the WebView's localStorage on the phone.

Build: `./build.sh` (uses the JDK + Android SDK in `~/.cache/fritz-apk`; signing key is
`~/.config/fritz/isavemoney.keystore`, never committed). Download the APK from Releases.
