package com.aurum.scalp;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.*;
import java.net.URI;
import java.util.*;
import javax.net.ssl.SSLParameters;

/** Minimal SignalR JSON hub transport: handshake, explicit subscription and bounded messages. */
class BiquoteStream extends WebSocketClient {
    interface Listener {void date(String value);void status(String value,boolean subscribed);void tick(JSONObject tick);void state(boolean closed);void limited();}
    private final Listener listener;
    private boolean handshake;
    private String pending="";
    BiquoteStream(Listener listener){super(URI.create("wss://biquote.io/hubs/tick"),new org.java_websocket.drafts.Draft_6455(),Collections.singletonMap("User-Agent","AurumScalp/2.6"),12000);this.listener=listener;setConnectionLostTimeout(45);setSocketFactory(javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory());}
    @Override protected void onSetSSLParameters(SSLParameters parameters){parameters.setEndpointIdentificationAlgorithm("HTTPS");}
    @Override public void onOpen(ServerHandshake response){listener.date(response.getFieldValue("Date"));send("{\"protocol\":\"json\",\"version\":1}\u001e");listener.status("SignalR handshake",false);}
    @Override public void onMessage(String message){
        try{
            if(message.length()+pending.length()>200_000){close(1009,"Message exceeds limit");return;}
            pending+=message;int end;
            while((end=pending.indexOf('\u001e'))>=0){String part=pending.substring(0,end);pending=pending.substring(end+1);if(part.isEmpty())continue;JSONObject j=new JSONObject(part);
                if(!handshake){if(j.has("error"))throw new IllegalStateException("Handshake rejected");handshake=true;send("{\"type\":1,\"invocationId\":\"1\",\"target\":\"Subscribe\",\"arguments\":[[\"XAUUSD\"]]}\u001e");continue;}
                int type=j.optInt("type");
                if(type==3&&"1".equals(j.optString("invocationId"))){if(j.has("error"))throw new IllegalStateException("Subscription rejected");listener.status("Subscribed to XAUUSD",true);}
                if(type==7){close();listener.status("Provider closed stream; reconnect pending",false);}
                JSONArray args=j.optJSONArray("arguments");
                if(type==1&&args!=null&&"ReceiveTick".equals(j.optString("target"))){JSONObject tick=args.optJSONObject(0);if(tick!=null&&"XAUUSD".equals(tick.optString("symbol")))listener.tick(tick);}
                if(type==1&&args!=null&&"ReceiveSubscriptionState".equals(j.optString("target"))){JSONArray states=args.optJSONArray(0);if(states!=null)for(int i=0;i<states.length();i++){JSONObject s=states.optJSONObject(i);if(s!=null&&"XAUUSD".equals(s.optString("symbol"))){String value=s.optString("state");if("open".equals(value)||"closed".equals(value))listener.state("closed".equals(value));};}}
            }
        }catch(Exception invalid){listener.status("Invalid stream response; reconnect pending",false);close(1002,"Invalid SignalR message");}
    }
    @Override public void onClose(int code,String reason,boolean remote){listener.status("Disconnected; retry pending",false);}
    @Override public void onError(Exception failure){if(failure.getMessage()!=null&&failure.getMessage().contains("429"))listener.limited();listener.status("Stream connection unavailable; snapshot fallback",false);}
}
