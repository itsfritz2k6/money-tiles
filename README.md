# iSaveMoney (Android)

Offline budget app: budget periods, income, category budgets, accounts and transfers, recurring
transactions, savings goals, insights, search, CSV export and JSON backup/restore.

The whole app is `app/src/main/assets/index.html`, hosted by a WebView in `MainActivity.java`.
Data lives in the WebView's localStorage on the phone.

Build: `./build.sh` (uses the JDK + Android SDK in `~/.cache/fritz-apk`; signing key is
`~/.config/fritz/isavemoney.keystore`, never committed). Download the APK from Releases.
