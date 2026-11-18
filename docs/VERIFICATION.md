# Aurum Scalp 2.9.0 verification

The signed APK is package `com.aurum.scalp`, version code 29. It installed as an update over 2.8.0 on the Android 11 emulator. The signed release and installed APK both produced SHA-256 `f03919fb9a205915c826de6a670fb44778d8f9102d080fa63fd61ba5651be4be`. APK Signature Scheme v2 and v3 verified against the original Aurum signing certificate.

## Build and regressions

- 138 JVM tests passed. Debug and release lint reported no issues.
- All 11 Node chart model tests and 25 browser checks passed.
- The scheduling regression tested 1,000 randomized phases and 0–20 seconds of provider lag for each macro timeframe in simulation. A separate test delivered a 1H event once at 170 seconds and suppressed a stale event beyond the frame window.
- The cooldown regressions exercise short and long Retry-After values, a shared REST hold while stream claims remain available, and endpoint-specific 403 backoff.
- Alert tests cover persisted watermarks, a brief reconnect, event deduplication and an overlapping flipped pin. Session tests cover winter and summer New York rollover.

```sh
./gradlew testDebugUnitTest assembleDebugAndroidTest assembleRelease lint lintRelease
node --test tests/chart.test.cjs
python3 tests/browser_qa.py
python3 tests/replay/run.py
```

## Android and live provider

The release and test APK were signed with the existing key and installed on the Android 11 emulator. The instrumented suite includes separate alert notifications plus their group summary, chart gestures, and native feed/session fixtures.

```text
PASS: 12 Android biquote/feed checks; 12 repository/chart audit checks; 19 monitoring/notification/UI checks; 18 advanced chart checks; 14 deep-debug regression checks; 9 real-provider checks
```

```text
PASS real Android biquote TLS REST + SignalR; source=biquote · XAUUSD · MID analysis; quote=● LIVE; MID=3739.6099999999997; stream=Subscribed to XAUUSD; startupAllFramesAndStreamMs=5914; initialQuoteAgeMs=1179; 1m=300 closed / 18 zones / 0 gaps; 5m=288 closed / 20 zones / 1 gaps; 15m=192 closed / 10 zones / 2 gaps; 1H=168 closed / 10 zones / 0 gaps; 4H=180 closed / 12 zones / 0 gaps
Sample seconds=120; startedOpen=true; streamMessages=244; additionalSubscriptions=0; maxObservedMessageGapMs=2005; HTTP attempts=2; tailFrames=1
```

The live run observed a short connection and data sample through the emulator's test route. A separate direct 5m request with `limit=5` returned five closed bars plus one forming bar. Latency and gaps in the observed sample vary with market activity, local transport and provider uptime.

## Limits of this verification

- **Not run** — 30-minute market soak, live alert emission at a real 1H boundary, one-hour device battery or data measurement, and eight-hour screen-off monitoring on an Android 14 or 15 phone.
- **Not run** — Android 15 system timeout callback on a physical device. OS policies can defer periodic stop detection. Android force-stop can prevent it until the app is opened again.

[Recorded results](evidence/android-2.9.txt) · [Chronological replay](evidence/zone-replay.txt) · [Audit follow-up](DEBUG_AUDIT.md)
