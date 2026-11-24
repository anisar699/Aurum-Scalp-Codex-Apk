# Aurum Scalp

Aurum Scalp is a native Android app for watching XAU/USD support and resistance with live gold prices. It combines short-term execution context on 5m and 15m with market structure on 1H and 4H. Version **3.1.1** uses Android package `com.aurum.scalp` and runs on Android 8.0 (API 26) and later.

## Use the app

| Where | What you can do |
| --- | --- |
| **Market → Scalp View** | See the nearest break or retest watch and the confirmed bands on 5m, 15m, 1H and 4H alongside the spread and 5m volatility. Tap **Structure** for the higher-timeframe view. |
| **All zones → Distance filter** | Limit zones by USD from the nearest edge or by a multiple of the timeframe's ATR. Break and retest watches appear first, followed by confirmed zones, range references and wider structure. Tap a band for its width, independent swing reactions, defended and failed visits, last reaction, and distance. |
| **Chart** | Follow the selected timeframe. Use **Focus price** to fit the latest 45 candles and **Latest** to restore the default 100-candle view. **Layers** controls 1H/4H context, EMA50/200, forming candles and nearby zones. Tap a band or its label for details. |
| **Analysis** | Read the selected timeframe first. Swing structure, EMA/ADX trend, range and volatility have independent labels. |
| **Health** | Check quote age, last closed candle, last successful history fetch, provider backoff, available history and per-timeframe alert readiness. |

### Background monitoring and alerts

In **Health**, allow notifications and tap **Start background monitoring**. Expand the notification to see the live price and nearest support (`S`) and resistance (`R`) for **5m, 15m, 1H and 4H**. The collapsed view previews 1H. **Open chart** opens the 1H chart and **Stop monitoring** ends the service. A `?` identifies a retest or range watch, `!` marks a break pending, `*` marks saved candles, and `†` marks stale candles.

Tap a zone and choose **Pin alert** to set an approach, break or retest alert. Each timeframe can have one pin. A pin can follow an overlapping replacement or a confirmed flip. Its trigger works alongside automatic alerts when the same timeframe is selected. Manage pins from **Health**. Automatic alerts default to 1H and 4H. Breaks and retests use closed candles from their own timeframe. The event window is five minutes for 1H and 4H and 90 seconds for shorter frames. A fresh matching quote is required. Late confirmations show their age and processed closes are saved to prevent repeat alerts. The notification group keeps up to ten recent alerts.

Android controls notification permissions and foreground service limits. Android 15 applies a background time budget to data-sync services. Start monitoring from Health. The app reports a stopped service when Android delivers the stop callback or permits the periodic health check. The shade refreshes at most every five seconds while the screen is on and every 15 seconds while it is off. [Verification](docs/VERIFICATION.md)

## Price and zone data

Biquote provides anonymous XAU/USD quotes, a SignalR stream and REST history. The app connects directly to Biquote and uses SignalR keep-alives. REST snapshots cover a delayed or disconnected stream. A REST HTTP 429 pauses REST according to the shared per-IP limit and exact Retry-After, while the live stream has its own retry schedule. HTTP 401/403 applies to the affected resource. Health shows active cooldowns and their reasons. The displayed MID is the average of bid and ask. Bid, ask and spread appear separately. If a newer closed candle becomes the reference, the app identifies it as a candle reference. Biquote prices can differ from a broker's execution prices and the free feed has no delay guarantee. [Biquote API documentation](https://biquote.io/docs/) · [Terms](https://biquote.io/terms.html)

### Support and resistance evidence

Aurum measures independent swing reactions and observes visits to an existing band after price has first moved away. A defended visit comes back from the valid side and closes at least 0.25 ATR clear of the near edge within three bars of the touch. A failed visit closes past the opposite edge and buffer. The counters are historical observations. A swing reaction and a visit may describe the same move.

After one buffered adverse close the band is shown as **Break pending** and its approach alerts pause. The second qualifying closed candle confirms the break. Market, Scalp View and Chart show the nearest break or retest watch alongside the confirmed areas. All Zones places break and retest watches first. The status notification uses an exclamation mark for a pending band.

The replay compares bands with random bands of the same width and similar distance from price. Five-candle swing wings, stronger rejection filters and a recency adjustment were tested offline. The holdout did not establish a consistent improvement. [Method and results](docs/VERIFICATION.md#support-and-resistance-research)

Zone analysis uses valid provider-confirmed closed candles. Swing confirmation waits for right-side closes, zone clustering limits width, and watch areas identify unconfirmed references or zones awaiting a retest. The chart can separately show forming or pending previews from the feed. EMA overlays use confirmed closes and appear only when enough history is available. The renderer uses bundled TradingView Lightweight Charts. [Third-party components](docs/THIRD_PARTY.md)

History fetches follow UTC candle closes for the displayed, alert, pinned and notification-shade timeframes. The app merges a five-bar tail between hourly full corrections. Health shows the requested and returned counts. A history response must contain the latest closed candle in the displayed series before that timeframe is verified as current. Support and resistance can load with 40 closed bars, while the trend readout needs 80. The Analysis prior trading day uses the New York 17:00 rollover, including daylight saving time. [Chronological replay](docs/evidence/zone-replay.txt)

## Build from source

Install **JDK 17**, **Android SDK Platform 35** and **Android SDK Build-Tools 35.0.0**. Open the repository in Android Studio to configure the SDK, or set `ANDROID_HOME` to the SDK directory. The Gradle wrapper uses Gradle 8.9.

```sh
./gradlew :app:assembleDebug
```

Debug APK output is `app/build/outputs/apk/debug/app-debug.apk`. With a device or emulator connected, install it with `./gradlew :app:installDebug`. On Windows, use `gradlew.bat`.

Build an unsigned release with `./gradlew :app:assembleRelease`. The output is `app/build/outputs/apk/release/app-release-unsigned.apk`. Use your own signing configuration for distribution. An in-place update of the existing signed Aurum app requires its original release key. Keep a separate encrypted backup of the keystore and verify that its certificate matches the installed release before signing an update.

## Test and verify

```sh
./gradlew :app:testDebugUnitTest :app:lint :app:lintRelease :app:assembleDebugAndroidTest
node --test tests/chart.test.cjs
python3 tests/replay/run.py
```

The replay uses the checked-in test fixtures. Add `--live` to sample current provider history. It checks eligibility, touch and reaction scoring against seeded random controls. The small fixtures are for integration checks. Browser integration checks use Python Playwright and Chromium via `python3 tests/browser_qa.py`.

For Android instrumentation, install the debug app and its matching debug test APK, then run:

```sh
./gradlew :app:installDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.aurum.scalp.test/com.aurum.scalp.FeedChecks
```

Add `-e live true` after `-w` to include the real-provider checks. The release verification records the build, Android emulator and provider checks. GitHub Actions runs the unit tests, lint, unsigned release build and chart model tests for pushes and pull requests. [Verification and device limits](docs/VERIFICATION.md)
