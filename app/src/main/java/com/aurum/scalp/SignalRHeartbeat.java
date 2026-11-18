package com.aurum.scalp;

final class SignalRHeartbeat {
    static final String PING = "{\"type\":6}\u001e";
    enum Action { WAIT, PING, RECONNECT }
    private long received, sent;
    private boolean active;

    synchronized void start(long now) { received = sent = now; active = true; }
    synchronized void received(long now) { received = now; }
    synchronized void stop() { active = false; }

    synchronized Action poll(long now) {
        if (!active) return Action.WAIT;
        if (now - received >= 35_000) return Action.RECONNECT;
        if (now - sent < 15_000) return Action.WAIT;
        sent = now;
        return Action.PING;
    }
}
