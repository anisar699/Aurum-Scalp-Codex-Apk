# Aurum Scalp

Aurum Scalp is a native Android app for watching XAU/USD support and resistance with live gold prices. It combines short-term execution context on 5m and 15m with market structure on 1H and 4H. Version **2.8.0** uses Android package `com.aurum.scalp` and runs on Android 8.0 (API 26) and later.

## Use the app

| Where | What you can do |
| --- | --- |
| **Market → Scalp View** | See the nearest confirmed bands on 5m, 15m, 1H and 4H alongside the bid/ask spread and 5m volatility. Tap **Structure** for the higher-timeframe view. |
| **All zones → Distance filter** | Limit zones by USD from the nearest edge or by a multiple of the timeframe's ATR. Confirmed zones, watch areas and wider structure each have their own section. Tap a band for its width, reaction count, last reaction and distance. |
| **Chart** | Follow the selected timeframe. Use **Focus price** to fit the latest 45 candles and **Latest** to restore the default 100-candle view. **Layers** controls 1H/4H context, EMA50/200, forming candles and nearby zones. Tap a band or its label for details. |
| **Analysis** | Read the selected timeframe first. Swing structure, EMA/ADX trend, range and volatility have independent labels. |
| **Health** | Check quote age, last closed candle, last successful history fetch, provider backoff, available history and per-timeframe alert readiness. |

### Background monitoring and alerts

In **Health**, allow notifications and tap **Start background monitoring**. Expand the notification to see the live price and nearest support (`S`) and resistance (`R`) for **5m, 15m, 1H and 4H**. The collapsed view previews 1H. **Open chart** opens the 1H chart and **Stop monitoring** ends the service. A `?` identifies a watch, `*` marks saved candles, and `†` marks stale candles.

Tap a zone and choose **Pin alert** to set an approach, break or retest alert. Each timeframe can have one pin. Broken-zone watches can be pinned for retests. Manage pins from **Health**. Automatic alerts default to 1H and 4H. Breaks and retests use closed candles from their own timeframe. When a candle arrives before its matching quote, the monitor checks the event once the quote catches up within the 90-second event window. Initial snapshots and reconnects prime alerts silently.

Android controls notification permissions and foreground service limits. Android 15 applies a background time budget to data-sync services. Monitoring starts on demand from Health and stops when the system ends the service or the app is force-stopped. See the platform checks for tested devices. [Verification](docs/VERIFICATION.md)

## Price and zone data

Biquote provides anonymous XAU/USD quotes, a SignalR stream and REST history. The app connects directly to Biquote. A REST snapshot is the fallback when the stream is delayed or disconnected. The displayed MID is the average of bid and ask. Bid, ask and spread appear separately. If a newer closed candle becomes the reference, the app identifies it as a candle reference. Biquote prices can differ from a broker's execution prices and the free feed has no delay guarantee. [Biquote API documentation](https://biquote.io/docs/) · [Terms](https://biquote.io/terms.html)

Zone analysis uses valid provider-confirmed closed candles. Swing confirmation waits for right-side closes, zone clustering limits width, and watch areas identify unconfirmed references or zones awaiting a retest. The chart can separately show forming or pending previews from the feed. EMA overlays use confirmed closes and appear only when enough history is available. The renderer uses bundled TradingView Lightweight Charts. [Third-party components](docs/THIRD_PARTY.md)

Health displays delivered depth and recent gaps. One verified sample returned **168 of 1,000 requested 1H candles** and **180 of 360 requested 4H candles**. The local cache keeps confirmed history and incorporates provider corrections. [History and replay results](docs/VERIFICATION.md#history-and-replay)

## Build from source

Install **JDK 17**, **Android SDK Platform 35** and **Android SDK Build-Tools 35.0.0**. Open the repository in Android Studio to configure the SDK, or set `ANDROID_HOME` to the SDK directory. The Gradle wrapper uses Gradle 8.9.

```sh
./gradlew :app:assembleDebug
```

Debug APK output is `app/build/outputs/apk/debug/app-debug.apk`. With a device or emulator connected, install it with `./gradlew :app:installDebug`. On Windows, use `gradlew.bat`.

Build an unsigned release with `./gradlew :app:assembleRelease`. The output is `app/build/outputs/apk/release/app-release-unsigned.apk`. Use your own signing configuration for distribution. An in-place update of the existing signed Aurum app requires its original release key.

## Test and verify

```sh
./gradlew :app:testDebugUnitTest :app:lint :app:lintRelease :app:assembleDebugAndroidTest
node --test tests/chart.test.cjs
python3 tests/replay/run.py
```

The replay uses the checked-in test fixtures. Add `--live` to sample current provider history. It measures zone coverage and reactions over later closed bars. Browser integration checks use Python Playwright and Chromium via `python3 tests/browser_qa.py`.

For Android instrumentation, install the debug app and its matching debug test APK, then run:

```sh
./gradlew :app:installDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.aurum.scalp.test/com.aurum.scalp.FeedChecks
```

Add `-e live true` after `-w` to include the real-provider checks. The 2.8.0 release passed **128 JVM tests**, **74 Android 11 app checks** and **9 live-provider checks**. Recorded results include the native notification shade and the open-market Biquote sample. [Verification and device limits](docs/VERIFICATION.md)
