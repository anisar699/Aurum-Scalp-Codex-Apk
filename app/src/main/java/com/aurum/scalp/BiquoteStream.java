package com.aurum.scalp;

import java.net.URI;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

/** SignalR JSON handshake, subscription and hub-level liveness. */
class BiquoteStream extends WebSocketClient {
    interface Listener {
        void date(String value);
        void status(String value, boolean subscribed);
        void tick(JSONObject tick);
        void state(boolean closed);
        void limited();
        default void refused(int status) {}
    }

    static final String USER_AGENT = "AurumScalp/" + BuildConfig.VERSION_NAME + " (Android; XAUUSD viewer)";
    private final Listener listener;
    private final SignalRHeartbeat heartbeat = new SignalRHeartbeat();
    private boolean handshake;
    private String pending = "";

    BiquoteStream(Listener listener) {
        super(URI.create("wss://biquote.io/hubs/tick"), new Draft_6455(),
            Collections.singletonMap("User-Agent", USER_AGENT), 12_000);
        this.listener = listener;
        setConnectionLostTimeout(45);
        setSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory());
    }

    @Override protected void onSetSSLParameters(SSLParameters parameters) {
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
    }

    @Override public void onOpen(ServerHandshake response) {
        listener.date(response.getFieldValue("Date"));
        send("{\"protocol\":\"json\",\"version\":1}\u001e");
        listener.status("SignalR handshake", false);
    }

    void maintain() {
        if (!isOpen()) return;
        switch (heartbeat.poll(elapsed())) {
            case RECONNECT -> close(1000, "SignalR idle timeout");
            case PING -> {
                try { send(SignalRHeartbeat.PING); }
                catch (org.java_websocket.exceptions.WebsocketNotConnectedException ignored) {
                    // The socket closed between isOpen and send.
                }
            }
            default -> {}
        }
    }

    @Override public void onMessage(String message) {
        try {
            if (message.length() + pending.length() > 200_000) {
                close(1009, "Message exceeds limit");
                return;
            }
            pending += message;
            int end;
            while ((end = pending.indexOf('\u001e')) >= 0) {
                String part = pending.substring(0, end);
                pending = pending.substring(end + 1);
                if (part.isEmpty()) continue;
                JSONObject packet = new JSONObject(part);
                heartbeat.received(elapsed());
                if (!handshake) {
                    if (packet.has("error")) throw new IllegalStateException("Handshake rejected");
                    handshake = true;
                    heartbeat.start(elapsed());
                    send("{\"type\":1,\"invocationId\":\"1\",\"target\":\"Subscribe\",\"arguments\":[[\"XAUUSD\"]]}\u001e");
                } else {
                    receive(packet);
                }
            }
        } catch (Exception invalid) {
            listener.status("Invalid stream response; reconnect pending", false);
            close(1002, "Invalid SignalR message");
        }
    }

    private void receive(JSONObject packet) {
        int type = packet.optInt("type");
        if (type == 3 && "1".equals(packet.optString("invocationId"))) {
            if (packet.has("error")) throw new IllegalStateException("Subscription rejected");
            listener.status("Subscribed to XAUUSD", true);
        }
        if (type == 7) close(1000, "Provider closed SignalR");
        JSONArray args = packet.optJSONArray("arguments");
        if (type != 1 || args == null) return;
        String target = packet.optString("target");
        if ("ReceiveTick".equals(target)) {
            JSONObject tick = args.optJSONObject(0);
            if (tick != null && "XAUUSD".equals(tick.optString("symbol"))) listener.tick(tick);
        } else if ("ReceiveSubscriptionState".equals(target)) {
            JSONArray states = args.optJSONArray(0);
            if (states == null) return;
            for (int i = 0; i < states.length(); i++) {
                JSONObject state = states.optJSONObject(i);
                if (state == null || !"XAUUSD".equals(state.optString("symbol"))) continue;
                String value = state.optString("state");
                if ("open".equals(value) || "closed".equals(value)) listener.state("closed".equals(value));
            }
        }
    }

    @Override public void onClose(int code, String reason, boolean remote) {
        heartbeat.stop();
        listener.status(reason.equals("SignalR idle timeout") ? "SignalR idle timeout; retry pending"
            : "Stream disconnected (" + code + "); retry pending", false);
    }

    @Override public void onError(Exception failure) {
        String message = failure.getMessage();
        if (message != null) {
            if (message.contains("429")) listener.limited();
            else if (message.contains("401")) listener.refused(401);
            else if (message.contains("403")) listener.refused(403);
        }
        listener.status("Stream connection unavailable; snapshot fallback", false);
    }

    private static long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
}
