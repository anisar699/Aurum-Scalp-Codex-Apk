# Aurum Scalp verification

## 3.2.0 setup and chart studies

- The setup card distinguishes watching, a confirmed 5m reclaim, live pressure beyond invalidation and a closed invalidation. A reclaim cannot precede confirmation of the anchor zone. Fixtures cover both sides, incomplete history, a missing candle and quotes that outrun the latest closed 5m bar.
- Session ranges are built from complete, aligned candle windows. Fixtures cover DST, weekends, missing history, a range still forming and weekly completeness.
- Sweep fixtures cover a wick through either side followed by a close back inside, a close outside, a gap, and a candle that is still forming. Browser checks verify that session lines begin after availability and that markers apply only to confirmed candles.
- The zone replay fixture output matches 3.1.1.

The release build and debug/release lint passed. Local checks include 156 JVM tests, 11 chart-model tests and 33 browser assertions. The signed APK installed over 3.1.1 on Android 11. The installed SHA-256 matched the delivered APK.

Signed package `com.aurum.scalp`, version code 33. APK SHA-256 `2b8311bef66893d7aa508a87716c29c8e29fc38417460fb2139fcbf47440d62b`.

## 3.1.1 reliability fixes

- A delayed REST response cannot mark an unrelated newer saved quote as live. A valid same-timestamp snapshot can establish the on-device quote before the first stream tick.
- History responses must include the latest retained closed candle to verify that timeframe. Older or empty responses preserve saved history without renewing its verified status. Provider withdrawals remove the affected candle even when history falls below the 40-bar analysis threshold.
- A repository session must still be active when it reserves a provider request. Stopping the repository releases its reservations before a new session can claim them.
- The ten-minute approach cooldown survives pauses and temporary zone removal. Fresh quotes prime the approach state again. Automatic alerts still run on a pinned zone when the timeframe is selected, with a single notification for matching automatic and pinned events.
- The first confirmed chart history opens at the recent-candle range, including when a live preview arrived before the history.

The new regression cases cover old quotes, older history, a withdrawn candle, reconnecting during cooldown, temporary zone removal, pinned alerts, and late chart history. The fixture replay output matches 3.1.0.

Gradle unit tests, debug and release lint and the release build passed. The test totals include 147 JVM checks, 11 JavaScript chart-model checks and 30 Chromium chart checks.

The signed release is package `com.aurum.scalp`, version code 32. It installed over 3.1.0 on the Android 11 emulator, and the installed file matched the signed APK. APK SHA-256 `d00483fe0089af3bdcdcd6d9212e7e42248b9ef928ac5a88fab838a5c632638c`.

## Support and resistance research

The 3.0 audit identified two reproducible differences between the previous replay and the app. The fixture replay had no distance limit, and the archive ranking test included bands in **Break pending**. Both runners now use the app's confirmed selection with a fixed 3 ATR research radius and report results by selection and by touch. The app's user-selectable distance filter still applies on screen. [Previous release research](evidence/support-resistance-3.0.txt)

### Six-bar reaction test

Each origin rebuilds the engine from the preceding closed bars. The nearest eligible band on each side is taken from the production selector. The next six strictly consecutive bars are used for the outcome. After the first touch, a reaction requires a later close at least 0.5 origin ATR clear of the band. Two consecutive closes beyond a buffer of max($0.05, 0.05 origin ATR) count as a break. This is a fixed research outcome. The engine itself uses a buffer from the band's seed volatility.

For each selected band, the replay evaluates 20 seeded random bands of the same width and on the same price side in each of two controls.

- The primary control samples the nearest edge within the same half-ATR distance bin. Bands containing the origin price are compared with randomized bands that also contain it.
- The sensitivity control samples uniformly from zero to three ATR. This can change the chance that a band is reached.

Controls are averaged per selection. Lift is the percentage-point difference between the observed rate and its control. The standard error uses paired, week-level clusters to account for dependence within the same market period. With fewer than eight sampled weeks the replay omits the standard error. Both selected directions are recorded and multiple candidates use the same deterministic draws at a given origin.

### Archive results

The external MetaTrader archive has 465,353 validated 5m XAUUSD candles from January 2020 to August 2026. The clock time zone is unverified and the higher timeframes are built from the reported 5m timestamps on a nominal UTC grid. The aggregation retains buckets with at least two thirds of their expected components, so some resampled bars are partial, particularly near session boundaries. Before 2024 is the development period. January 2024 to August 2026 is the later archive check. An earlier release used the same split for a different experiment. Biquote's native timestamped MID sample is separate and short, with just 7 and 10 confirmed-band touches for 1H and 4H. Dukascopy's historical endpoint returned HTTP 429 during verification. The audit's exact Dukascopy data and harness were unavailable. [MetaTrader archive](https://www.kaggle.com/datasets/mahdimasoumian/xauusd-2020-2026) · [Biquote API](https://biquote.io/docs/)

| Timeframe | Observed touches | Confirmed reaction / touch | Distance-matched random | Lift ± weekly SE |
| --- | ---: | ---: | ---: | ---: |
| 5m | 4,691 | 41.6% | 42.1% | −0.5 ± 0.4 pp |
| 15m | 2,822 | 41.3% | 41.8% | −0.5 ± 0.5 pp |
| 1H | 618 | 39.5% | 40.2% | −0.7 ± 0.8 pp |
| 4H | 426 | 39.7% | 39.4% | +0.3 ± 1.2 pp |

Confirmed bands had no clear reaction advantage over the matched control. Their break rate was usually higher than the uniform control, with a much smaller difference after matching distance. Five-bar swing wings at 0.5, 0.75 and 1.0 ATR rejection, and the 0.75 ATR variant with a 0.25 ATR recency credit that halves every 24 bars, did not establish an advantage. Using the shorter typical first-sync histories gave a similar result. The release keeps the current production detection and edge ordering. Same-role overlap continues to describe where frames agree.

### Watch areas

A separate location test compares nearby turns for confirmed swings, break-pending bands and retest watches as distinct groups. Its non-overlapping future windows use 72, 64, 48 and 24 bars on 5m, 15m, 1H and 4H. A turn is a future five-wing high or low within 0.3 origin ATR of a touched band. The wings must be continuously observed under the engine's session-gap rules. This is a per-band turn-location test with the same controls, rather than the audit's all-band turn-capture test.

The 1H retest watches had +1.5 percentage points of turn lift (weekly SE 1.2) and +1.6 percentage points of directional reaction lift (SE 1.1). Results varied by timeframe. The 4H break-pending turn result used only 13 touched bands. Market, Scalp View and the advanced chart now show the nearest lifecycle watch alongside confirmed zones. All Zones places break and retest watches first. The one-close pending state and two-close break/retest requirements still govern alert eligibility. Reaction counts, visit outcomes and rejection size are shown as historical observations.

[Detailed development, later-period, Biquote and first-sync output](evidence/support-resistance-3.1.txt) · [Fixture integration output](evidence/zone-replay.txt)

### Reproduce the checks

The external archive ZIP SHA-256 is `483117c86229009a09d92facb8095667d9be8f14d2ccb02fa8d10f05b2a523ea`. To include native Biquote data, supply `--biquote` with a directory containing 5m, 15m, 1h and 4h JSON responses with the HTTP Date in the `_asOf` field.

```sh
python3 tests/replay/run.py
python3 tests/replay/compare_strength.py XAUUSD-archive.zip --split development
python3 tests/replay/compare_strength.py XAUUSD-archive.zip --split holdout
python3 tests/replay/compare_strength.py XAUUSD-archive.zip --split holdout --first-sync
```

## 3.1.0 build and signing

- Gradle release build, debug and release lint, and 144 JVM unit tests passed.
- 11 JavaScript chart model tests, 28 browser integration checks, and the fixture replay passed.
- The signed release is package `com.aurum.scalp`, version code 31. The signing certificate matches v3.0.0.
- Signed APK SHA-256 `ec1fc8ae54e220ba05bbd4f2468e66b359894d2b548547ac2243bbac47d79dce`.

## 3.1.0 Android 11 emulator

The signed APK updated the installed 3.0.0 package in place. The installed package SHA-256 matched the delivered APK. After an initial chart timeout on the emulator, the final run passed all 76 deterministic Android checks, including the new Scalp View watch regression, and all nine live Biquote checks.

```text
PASS: 12 Android biquote/feed checks; 12 repository/chart audit checks; 20 monitoring/notification/UI checks; 18 advanced chart checks; 14 deep-debug regression checks; 9 real-provider checks
```
