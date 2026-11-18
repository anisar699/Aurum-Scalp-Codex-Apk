package com.aurum.scalp;
import org.junit.Test;import static org.junit.Assert.*;import org.json.*;import java.util.*;
public class BiquoteStreamTest {
 static class Recorder implements BiquoteStream.Listener {
  int ticks,limits;boolean subscribed,closed;List<String> status=new ArrayList<>();
  public void date(String s){} public void status(String s,boolean v){status.add(s);subscribed=v;}public void tick(JSONObject j){ticks++;}public void state(boolean b){closed=b;}public void limited(){limits++;}
 }
 static class Socket extends BiquoteStream {
  final List<String> sent=new ArrayList<>();int closedCode;
  Socket(Recorder r){super(r);}@Override public void send(String s){sent.add(s);}@Override public void close(int c,String s){closedCode=c;}
 }
 @Test public void handshakeAndFragmentedEventsRequireSubscriptionAck(){Recorder r=new Recorder();Socket s=new Socket(r);s.onMessage("{}\u001e");assertTrue(s.sent.get(0).contains("Subscribe"));assertFalse(r.subscribed);s.onMessage("{\"type\":3,\"invocationId\":\"1\",\"result\":null}\u001e");assertTrue(r.subscribed);s.onMessage("{\"type\":1,\"target\":\"ReceiveTick\",\"arguments\":[{\"symbol\":\"XAU");assertEquals(0,r.ticks);s.onMessage("USD\"}]}\u001e");assertEquals(1,r.ticks);}
 @Test public void wrongSymbolsIgnoredAndClosedStateRecognized(){Recorder r=new Recorder();Socket s=new Socket(r);s.onMessage("{}\u001e");s.onMessage("{\"type\":1,\"target\":\"ReceiveTick\",\"arguments\":[{\"symbol\":\"BTCUSDT\"}]}\u001e{\"type\":1,\"target\":\"ReceiveSubscriptionState\",\"arguments\":[[{\"symbol\":\"XAUUSD\",\"state\":\"closed\"}]]}\u001e");assertEquals(0,r.ticks);assertTrue(r.closed);}
 @Test public void rejectedHandshakeAndOversizeMessagesClose(){Recorder r=new Recorder();Socket s=new Socket(r);s.onMessage("{\"error\":\"denied\"}\u001e");assertEquals(1002,s.closedCode);Socket big=new Socket(r);big.onMessage("x".repeat(200001));assertEquals(1009,big.closedCode);}
 @Test public void rateLimitErrorIsSharedWithRestGate(){Recorder r=new Recorder();Socket s=new Socket(r);s.onError(new Exception("Invalid status code: 429"));assertEquals(1,r.limits);assertFalse(r.subscribed);}
}
