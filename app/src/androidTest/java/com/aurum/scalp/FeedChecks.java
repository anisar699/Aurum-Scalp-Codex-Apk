package com.aurum.scalp;
import android.app.Instrumentation;import android.os.Bundle;import org.json.*;import java.util.*;
public class FeedChecks extends Instrumentation {
 private boolean live;
 @Override public void onCreate(Bundle args){super.onCreate(args);live=args!=null&&"true".equals(args.getString("live"));start();}
 private void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
 private boolean rejected(String body,long now){try{BiquoteCodec.quote(body,now);return false;}catch(Exception expected){return true;}}
 @Override public void onStart(){Bundle result=new Bundle();int count=0;try{
  long t=1767225600000L;JSONObject j=new JSONObject().put("symbol","XAUUSD").put("timestamp",java.time.Instant.ofEpochMilli(t).toString()).put("bid",4000).put("ask",4000.5);
  Market.Quote q=BiquoteCodec.quote(j.toString(),t+2000);check(q.mid()==4000.25,"MID quote");count++;
  check(rejected("null",t),"Null response");count++;
  check(rejected("<html>Unavailable</html>",t),"HTML response");count++;
  check(rejected(new JSONObject(j.toString()).put("ask",3999).toString(),t),"Crossed quote");count++;
  check(rejected(new JSONObject(j.toString()).put("timestamp",java.time.Instant.ofEpochMilli(t+120000).toString()).toString(),t),"Future quote");count++;
  check(rejected(new JSONObject(j.toString()).put("symbol","BTCUSDT").toString(),t),"Wrong symbol");count++;
  Market.Quote closed=BiquoteCodec.quote(new JSONObject(j.toString()).put("marketState","closed").toString(),t);check(Market.quoteState(closed,true,t).contains("CLOSED"),"Market closed state");count++;
  for(Market.Frame f:Market.Frame.values()){
   JSONArray bars=new JSONArray();for(int i=99;i>=0;i--)bars.put(new JSONObject().put("openTime",java.time.Instant.ofEpochMilli(t+i*f.duration()).toString()).put("open",4000).put("high",4002).put("low",3998).put("close",4001).put("isOpen",i==99));
   String body=new JSONObject().put("symbol","XAUUSD").put("interval",BiquoteCodec.interval(f)).put("bars",bars).toString();List<Market.Candle>b=BiquoteCodec.candles(body,f,t+200*f.duration(),80);check(b.size()==99&&b.get(0).time==t,"Sorted closed flags "+f.label);count++;
  }
  result.putString("stream","PASS: "+count+" Android biquote/feed checks; "+AuditChecks.run(this)+" repository/chart audit checks; "+MonitorChecks.run(this)+" monitoring/notification/UI checks"+"; "+ChartChecks.run(this)+" advanced chart checks; "+DebugChecks.run(this)+" deep-debug regression checks"+(live?"; "+LiveFeedChecks.run(this)+" real-provider checks":"")+"\n");finish(-1,result);
 }catch(Throwable e){result.putString("stream","FAIL after "+count+" checks: "+android.util.Log.getStackTraceString(e)+"\n");finish(0,result);}}
}
