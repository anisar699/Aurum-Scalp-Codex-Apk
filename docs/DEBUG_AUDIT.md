# Aurum Scalp 2.9.0 audit follow-up

| Audit items | Resolution |
| --- | --- |
| H-1, H-3 | Fetch currently needed histories around UTC closes, retry pending closes with a bounded schedule, and merge five-bar tails into cached history. Bootstrap and hourly correction requests use full windows. The shade still needs 5m, 15m, 1H and 4H in background mode. Manual Refresh asks for the visible timeframe and quote with per-resource spacing. A delayed 1H/4H confirmation stays eligible for five minutes and displays its age. |
| H-2 | Biquote publishes a shared per-IP REST limit and an unmetered SignalR stream. REST 429 preserves the shared REST cooldown. The established stream stays available and its reconnect failures have their own cooldown. REST 401/403 uses endpoint-specific exponential retry. Active holds persist and Health shows their status and retry time. |
| M-1, M-2, M-8 | Add SignalR pings and hub-idle detection. Estimate server time from TLS response Date and request latency and slew corrections on elapsed time. Brief interruptions preserve closed-candle watermarks. Persist processed closes to recognize a recent confirmation once after a restart. |
| M-3, M-4 | Group up to ten recent event notifications, with each event independently visible. Coalesce repository UI callbacks, evaluate alerts on one worker, cache notification permission checks and slow the shade updates. |
| M-5 | Detect service stops through lifecycle callbacks and a best-effort periodic job. Report the reason in Health and post a stopped notification when Android permits it. Long background monitoring remains subject to OEM and Android restrictions. |
| M-6, M-7 | Resolve pins to the best nearby same-frame band and carry their context through a confirmed flip. Report three closed snapshots without a replacement. Calculate the prior trading day at 17:00 New York time and require four hours of observed coverage. |
| L-1, L-2, L-4, L-5, L-6, L-8 | Use the same versioned User-Agent for both transports, two-decimal native chart labels, and outward-rounded notification bands. Detach the WebView before destruction. Follow one same-origin HTTPS redirect. Use 40 bars for zones and keep the higher minimum for trend metrics. Across time gaps, use the first bar's range for ATR. |
| L-7 | Simplify Retry-After parsing and remove the former unused instance state. Biquote's adapter requires the UTC grid; the generic candle model still accepts explicitly represented hour-aligned H4 broker fragments. The separate rules have coverage in the codec and model tests. |
| L-9 | Rework the touched transport and scheduling code and add CI for Android and chart tests. Keep release signing outside the repository and document key backup and update requirements. |

UTC display and English strings remain in this release. Translation, extended walk-forward measurements, broker offsets and server-side monitoring were deferred. The historical replay is a small developer-side sample.

See the run results and device limits in [VERIFICATION.md](VERIFICATION.md).

---

# Aurum 2.6: thorough debug audit

Scope: repository/stream lifecycle, rate limits, provider JSON/clock/cache, forming preview, zone engine and indicator calculations, native UI, chart data transforms, rendering, accessibility and background alerts. This audit compares the delivered 2.5 source with the 2.6 fixes. Controlled fixtures are in test sources only; production uses actual biquote data.

## Screenshots: expected behaviour

- The screenshots were taken on Sunday 15 November 2026. Friday 13 November's last XAUUSD quote and `market closed` are consistent with the provider's actual closed state. A successful Sunday fetch does not make Friday's last closed bar new. 168 H1 bars are the provider's supplied depth, despite requesting more.
- The H1 chart displayed about100 candles across a strong decline, explaining its large vertical range. A wide historical price range is different from zone width. The clustering caps, independent-reaction rules and causal break/retest engine were reviewed; no demonstrated width-cap violation was found. No additional artificial levels or narrowed-after-the-fact bands were added.
- `Stopped by you` with background monitoring off is expected. Notifications are separately configurable and require actual user-started background monitoring to continue when the app is hidden.

## Confirmed findings and fixes

| Finding in 2.5 | Result in 2.6 | Evidence |
|---|---|---|
| A repeated same-time/same-price WebSocket quote returned before setting quoteOnline. A new connection could stay SAVED despite receiving valid data. | Every valid current-connection receipt establishes reception; duplicate prices do not become new SmartAlerts events. Old market timestamps remain closed/stale. | DebugChecks: exact duplicate replay. |
| Callbacks were checked against repository session only. A closed socket's late status/state/date could overwrite a replacement socket in the same session. | Per-connection generation guards status, state, clock and ticks. A genuine late429 still protects the shared provider budget without releasing another connection's reservation. | DebugChecks: two generations and late429. |
| A fresh tick could clear an authoritative closed state; unknown subscription state was treated as open. | Explicit recent closed state is preserved. Unknown states are ignored. A closed subscription snapshot older than30s can expire only on a newer, fresh, nonstale tick; actual reopen events/REST metadata also reconcile state. | DebugRegressionTest, DebugChecks. |
| A same-timestamp REST snapshot was ignored while subscribed, including its market-state metadata. A delayed snapshot could also conflict with a newer stream state. | State revision guards keep newer stream state; same-time prices can remain stream-owned while explicit REST state is reconciled. | DebugChecks: same timestamp and an event during fetch. |
| Closed-market history deadlines could delay reopening data for10min. | Reopening advances quote/history deadlines while preserving in-flight reservations and every shared429 deadline. Successful quote/history scheduling reads current market state and commits under the same lock. | PollPolicy regression, callback checks. |
| A forming candle's close could remain at an older tick after a newer provider snapshot. | Snapshot HTTP-Date orders the close against observed ticks. Older snapshots/ticks cannot undo a newer close; extremes merge until actual confirmed history replaces the preview. | Three new FormingBook ordering tests and retained rollover/extreme tests. |
| The chart excluded watch references from its nearest readout, penalised them heavily in labels, omitted range fallbacks and called them only Watch. | Same native nearest/watch candidates, deduplicated IDs, explicit S/R watch/range roles and proximity-first current-frame labels. MID/collision protection remains, so not every band needs an axis label. Readout exposes the nearest watch bounds even if its label collides with MID. | Native payload checks, browser readout and real H1 UI check. |
| A zone containing current price was excluded from nearby-edge scale expansion. | A bounded expansion also considers that containing band. Far bands still cannot compress candles through unlimited scaling. | Node containing-band regression. |
| Historical OHLC correction that expanded the price scale could move the crosshair's selected price. | Rendering preserves selected UTC timestamp and actual price while suppressing internal mutation callbacks; corrected OHLC still updates. | crosshair-stress-before.txt reproduces failure; stronger browser regression passes after fix. |
| Any history-map replacement forced native zone cards to rebuild, even when displayed values/evidence/context were unchanged. | Full displayed/detail signature includes bounds, evidence, timestamps and context. Unchanged cards/focus targets survive; changed content still rebuilds. | DebugChecks preserves the actual View object across an equal analysis replacement. |
| A future last candle passed historyFresh because negative age was below the maximum age. Missing cache marketTime defaulted to effectively infinite time. | Freshness requires close<=trusted now. Missing cache time defaults to untrusted0, so closed history is not promoted using an invented clock. | Future-close unit test; offline/cache instrumentation retained. |
| Candle-reference price could be labelled as LIVE/CONNECTING in the advanced chart or when no quote existed. Weekend history was grouped with unknown-clock errors. | Shared price-state logic labels CANDLE REFERENCE; market closed, untrusted time, future candles and outdated data have distinct history labels. | Price/history-state unit tests and UI. |
| Renderer startup failure could leave a perpetual waiting chart; a lost WebView renderer had no recovery path. | Actionable update-WebView/native-map fallback on startup failure. Renderer loss closes the chart safely and leaves the native app usable. No permissive TLS or remote chart fallback. | Android deletes the renderer API to exercise startup fallback, then deliberately terminates the owned renderer. The application survives. |
| The candle legend's static aria-label hid its OHLC content from accessibility readers. | Accessible label updates to the same UTC/OHLC values being displayed, without continuously forcing TalkBack announcements. | Browser accessibility assertion. |

## Validation limits

See VERIFICATION.md for final counts and screenshots. Android11/API30 and WebView83 executed here; other Android/OEM versions still need their own device runs. Gold was closed during real-provider checks. Live forming/reopen mechanics were tested with controlled events, not a claim of measured open-market gold cadence. No free-feed uptime guarantee, Exness-price identity, trading accuracy percentage or profitability claim is made.
