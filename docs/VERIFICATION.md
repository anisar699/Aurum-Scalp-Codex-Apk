# Aurum Scalp 2.8.0 verification

2026-11-18. Package `com.aurum.scalp`, versionCode 28. The APK retained the existing Aurum signing identity and installed as an update. Its SHA-256 is `f7c2caf78cbe55eff3e0fd290aefb559e86dbfd62334ba965dbd9bd2f0bec6a9`. Signature verification, zip alignment, and the installed SHA-256 all matched.

## Build and regressions

- 128 JVM tests passed. Debug and release Android lint reported no issues.
- JavaScript model tests passed. Browser integration passed 25 checks with the bundled renderer, including historical correction, pan/zoom, Focus price, Latest, zone tap, 150% font and absence of remote renderer requests.
- The candle-before-quote regression produces exactly one break upon the matching tick. Separate tests cover quiet reconnect, stale-event suppression, independent timeframe watermarks and pinned-zone selection.

## Release-signed Android 11 emulator

```text
PASS: 12 Android biquote/feed checks; 12 repository/chart audit checks; 18 monitoring/notification/UI checks; 18 advanced chart checks; 14 deep-debug regression checks; 9 real-provider checks
```

```text
PASS native expanded shade rendered four requested frame rows and Open chart
PASS background notification published a new live price
android.title=String (Aurum · LIVE · 3,754.64 MID) -> android.title=String (Aurum · LIVE · 3,754.65 MID)
PASS Open chart notification action launched the chart
PASS chart opened on the 1H timeframe
The separate screen-off and battery-saver UI attempt timed out in the emulator input service.
```

## Biquote

```text
PASS real Android biquote TLS REST + SignalR; source=biquote · XAUUSD · MID analysis; quote=● LIVE; MID=3754.161; stream=Subscribed to XAUUSD; startupAllFramesAndStreamMs=5795; initialQuoteAgeMs=431; 1m=300 closed / 12 zones / 0 gaps; 5m=288 closed / 17 zones / 1 gaps; 15m=192 closed / 9 zones / 2 gaps; 1H=168 closed / 9 zones / 0 gaps; 4H=180 closed / 12 zones / 0 gaps
```

The open-market sample records a single emulator startup and initial quote age. Transport and closed-bar coverage are observable here. Continuous handset freshness or agreement with Exness requires a separate measurement.

## History and replay

The tested provider request returned 300/720 1m, 288/720 5m, 192/480 15m, 168/1,000 1H and 180/360 4H valid closed bars. The quality counter uses the zone engine's trading-adjacency rules, so regular breaks can appear as gaps on shorter frames. Health lists the latest returned count alongside cached depth. Missing bars are never synthesized.

See [zone replay](evidence/zone-replay.txt) for provider and archived samples by timeframe, trend and volatility. Selection uses only bars available at each decision. A later six-bar window records first touch, reaction before invalidation, and elapsed bars after first touch. These observations describe this sample. Response latency is measured separately and the replay does not estimate win rate.

## Platform coverage

Android 11 passed the release-signed app checks, real live-feed check, native notification-shade render and Open chart action. The separate screen-off/battery-saver run did not complete because emulator input timed out. The 150% font result covers the rendered chart on an Android-sized browser stage. Android 15 timeout and permission UI, TalkBack, physical-device startup/latency, Wi-Fi-to-mobile handoff and long-run battery behavior remain unverified in this test environment.

The source archive contains the app, build wrapper, tests, third-party notices and these verification results. Signing material, local SDK configuration and emulator-only routing are excluded.
