# Aurum Scalp 3.0.0 verification

The signed APK is package `com.aurum.scalp`, version code 30. The release installed over v2.9.0 on Android 11 with the same signing certificate. Signed and installed SHA-256 matched `3d166fa13f63da189c1c7623dd590a9f0809235787c09cf77865a71a97970f9f`.

## Support and resistance research

Carol Osler's New York Fed study found that published FX levels helped identify intraday trend interruptions, with results that varied by exchange rate and provider. Chung and Bellotti reported associations between previous bounces, elapsed time and later intraday reactions. These observations motivated testing repeat visits and recency on XAUUSD. [Osler](https://www.newyorkfed.org/research/epr/00v06n2/0007osle.html) · [Chung and Bellotti](https://arxiv.org/abs/2101.07410)

The independent MetaTrader archive contained 465,353 validated 5m XAUUSD bars, January 2020 to August 2026. It does not specify Biquote MID prices or a verified timestamp time zone. 15m, 1H and 4H were resampled on the archive's reported clock. Buckets needed at least two thirds of their expected 5m components. The current-provider check used dated Biquote MID snapshots collected on November 20, 2026. [XAUUSD MetaTrader archive](https://www.kaggle.com/datasets/mahdimasoumian/xauusd-2020-2026) · [Biquote](https://biquote.io/docs/)

Every decision reconstructed zones from the preceding closed candles, with the app's frame-specific history caps. Each side considered the nearest eligible band within three ATR. The next six consecutive candles measured a touch followed by a close 0.5 ATR clear of the band versus two adverse buffered closes. Strides kept observation windows from overlapping. The archive before 2024 was used for development. January 2024 to August 2026 and the Biquote sample were held out. The 0.35 evidence weight was picked using the development results.

| Holdout timeframe | Touched bands | Nearest-order reactions | Weighted reactions |
| --- | ---: | ---: | ---: |
| 5m | 4,691 | 2,083 | 2,086 |
| 15m | 2,822 | 1,244 | 1,242 |
| 1H | 618 | 271 | 271 |
| 4H | 426 | 185 | 185 |

Weighted ranking changed 25, 13, zero and two archive holdout choices respectively, and zero choices in the recent Biquote sample. The variation did not support shipping a different ranking. The production app uses edge distance and displays defended/failed visits, time since the last response and the mean swing rejection capped at two ATR per reaction. Same-role overlap requires at least half of the smaller band and an up-to-date candle window relative to the other loaded frames.

After a first adverse buffered close, the band moves to **Break pending** in the user interfaces and pauses approach alerts. The established two-close break and retest lifecycle continues. The tests cover evidence on both sides, interruption by a data gap, exclusion of unfinished candles, and a second close still delivering a break alert.

[Development and holdout counts](evidence/support-resistance-3.0.txt)

## Build and device verification

- 143 JVM unit tests, debug and release lint, and the release build passed.
- 11 JavaScript model tests and 27 browser chart checks passed.
- Signed Android 11 package update, Android regression checks and a live Biquote smoke test passed.

```text
PASS: 12 Android biquote/feed checks; 12 repository/chart audit checks; 19 monitoring/notification/UI checks; 18 advanced chart checks; 14 deep-debug regression checks; 9 real-provider checks
```

The external archive is required only to reproduce the developer-side comparison. Its ZIP SHA-256 is `483117c86229009a09d92facb8095667d9be8f14d2ccb02fa8d10f05b2a523ea`.

```sh
python3 tests/replay/compare_strength.py XAUUSD-archive.zip --split development
python3 tests/replay/compare_strength.py XAUUSD-archive.zip --split holdout --weights 0,0.35
```

A physical Android 14/15 long-run monitoring test was outside this release's verification. [Previous Android verification](https://github.com/anisar699/Aurum-Scalp-Codex-Apk/blob/57a44bd/docs/VERIFICATION.md)
