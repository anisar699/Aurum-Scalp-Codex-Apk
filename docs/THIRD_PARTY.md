# Third-party components

Runtime:
- TradingView Lightweight Charts 5.3.0, Apache 2.0. Bundled locally under app/src/main/assets/chart/vendor with its complete license. Upstream https://github.com/tradingview/lightweight-charts . The chart includes the library attribution logo and a visible TradingView attribution link; data attribution links to biquote. Custom candle/layer/update logic is Aurum code.
- Java-WebSocket1.5.7 (org.java-websocket:Java-WebSocket), MIT. Upstream https://github.com/TooTallNate/Java-WebSocket/tree/v1.5.7 . License in licenses/Java-WebSocket.txt. TLS uses Android's default trust store plus HTTPS hostname validation; no permissive verifier/certificate trust manager exists in production.
- SLF4J API2.0.9, MIT. Upstream https://www.slf4j.org/ . License extracted from the delivered jar in licenses/slf4j.txt. With no logging provider it falls back to NOP logging; a startup diagnostic may appear in logs.

Test only: JUnit4.13.2 and org.json20240303, resolved through Gradle, not embedded as application libraries. Android supplies org.json at runtime. Real sampled public data fixtures belong to this product's isolated test suite and are not shipped in the production APK.

Biquote is a remote service, not a bundled component. Documentation https://biquote.io/docs/ and terms https://biquote.io/terms.html . Free availability and provider rights/limits can change.
