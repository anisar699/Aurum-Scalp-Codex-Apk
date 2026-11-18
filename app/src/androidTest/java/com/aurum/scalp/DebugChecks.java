package com.aurum.scalp;
import android.app.*;import android.content.*;import android.os.*;import org.json.*;import java.io.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.atomic.*;
/** Real repository callbacks with isolated transport/session; fixtures stay in the test APK. */
final class DebugChecks {
 private static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 private static Field field(Class<?> c,String n)throws Exception{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}
 private static JSONObject q(long t,double price,String state)throws Exception{JSONObject j=new JSONObject().put("symbol","XAUUSD").put("timestamp",java.time.Instant.ofEpochMilli(t).toString()).put("bid",price-.1).put("ask",price+.1);if(state!=null)j.put("marketState",state);return j;}
 static int run(Instrumentation i)throws Exception{
  Context c=i.getTargetContext();File dir=new File(c.getCacheDir(),"debug-check-"+System.nanoTime());check(dir.mkdirs(),"Isolated debug cache");Context isolated=new ContextWrapper(c){@Override public File getFilesDir(){return dir;}};
  AtomicReference<String> response=new AtomicReference<>();AtomicReference<Runnable> during=new AtomicReference<>();MarketRepository repo=new MarketRepository(isolated,()->{},url->{Runnable r=during.get();if(r!=null)r.run();return response.get();});
  Class<?> sessionClass=Class.forName("com.aurum.scalp.MarketRepository$Session");Constructor<?> constructor=sessionClass.getDeclaredConstructor();constructor.setAccessible(true);Object session=constructor.newInstance();field(MarketRepository.class,"session").set(repo,session);field(sessionClass,"generation").setLong(session,1);
  MarketClock clock=(MarketClock)field(MarketRepository.class,"marketClock").get(repo);long now=System.currentTimeMillis();clock.sync(now,SystemClock.elapsedRealtime());
  Method listener=MarketRepository.class.getDeclaredMethod("streamListener",sessionClass,long.class);listener.setAccessible(true);BiquoteStream.Listener one=(BiquoteStream.Listener)listener.invoke(repo,session,1L);int count=0;
  try{
   repo.quote=new Market.Quote(now-86400000,4000,4000.2,true,true);one.tick(new JSONObject().put("symbol","XAUUSD").put("timestamp",java.time.Instant.ofEpochMilli(repo.quote.time).toString()).put("bid",repo.quote.bid).put("ask",repo.quote.ask));check(repo.quoteOnline&&repo.quote.marketClosed,"Duplicate saved quote proves current connection without pretending it is live");count++;
   one.state(true);one.tick(q(now,4002,null));check(repo.quote.marketClosed,"Authoritative closed state survives a fresh tick");count++;
   field(sessionClass,"stateAt").setLong(session,now-60000);one.tick(q(now,4002.5,null));check(!repo.quote.marketClosed,"Fresh nonstale ticks can expire an old closed subscription snapshot");count++;
   one.state(false);one.tick(q(now,4003,null));check(!repo.quote.marketClosed&&repo.quote.mid()==4003,"Explicit reopening restores quote and price");count++;
   one.tick(q(now,4004,null));check(repo.quote.mid()==4004,"Different price in same second is accepted");one.tick(q(now-1000,3900,null));check(repo.quote.mid()==4004,"Older stream tick cannot overwrite latest quote");count++;
   field(sessionClass,"generation").setLong(session,2);BiquoteStream.Listener two=(BiquoteStream.Listener)listener.invoke(repo,session,2L);two.status("New stream subscribed",true);one.state(true);one.status("Old stream disconnected",false);one.date("Fri, 01 Jan 2010 00:00:00 GMT");one.tick(q(now,3800,"closed"));check(!repo.quote.marketClosed&&repo.quote.mid()==4004&&repo.streamStatus.equals("New stream subscribed")&&repo.marketNow()>=now,"Obsolete connection cannot alter state, status, clock or quote");count++;
   Method fetch=MarketRepository.class.getDeclaredMethod("fetchQuote",sessionClass,boolean.class);fetch.setAccessible(true);response.set(q(now,4004,"closed").toString());fetch.invoke(repo,session,true);check(repo.quote.marketClosed,"Same-timestamp snapshot updates market metadata while stream is subscribed");count++;
   two.state(false);response.set(q(now,4004,"open").toString());during.set(()->two.state(true));fetch.invoke(repo,session,true);check(repo.quote.marketClosed,"In-flight old REST state cannot undo a newer stream state");during.set(null);count++;
   two.state(true);response.set(q(now,4004,"closed").toString());during.set(()->two.state(false));fetch.invoke(repo,session,true);during.set(null);PollPolicy poll=(PollPolicy)field(MarketRepository.class,"policy").get(repo);java.util.Map<?,?> due=(java.util.Map<?,?>)field(PollPolicy.class,"due").get(poll);check(!repo.quote.marketClosed&&((Long)due.get("quote"))-SystemClock.elapsedRealtime()<=15000,"Opening during an old closed REST response schedules the open-market fallback interval");count++;
   two.state(false);repo.quote=new Market.Quote(now+86400000,5000,5000.2);two.tick(q(now,4005,null));check(repo.quote.mid()==4005,"Valid current stream replaces an invalid future cached quote");count++;
   long bucket=now/Market.Frame.H1.duration()*Market.Frame.H1.duration();List<Market.Candle> bars=new ArrayList<>();for(int n=80;n>0;n--)bars.add(new Market.Candle(bucket-n*3600000,4000,4002,3990,4000));
   ZoneEngine.Zone near=new ZoneEngine.Zone(Market.Frame.H1,4005,4006,3,false,false,false,1,bucket-3600000,bucket);
   ZoneEngine.Zone far=new ZoneEngine.Zone(Market.Frame.H1,4040,4041,3,false,false,false,20,bucket-7200000,bucket);
   ZoneEngine.Zone watch=new ZoneEngine.Zone(Market.Frame.H1,4001,4002,3,false,true,false,0,bucket-3600000,0,true);
   repo.analyses=Collections.singletonMap(Market.Frame.H1,new ZoneEngine.Analysis(Market.Frame.H1,bars,Arrays.asList(near,far),Collections.singletonList(watch),3,0,"range"));repo.quote=new Market.Quote(now,4000,4000.2);
   JSONObject payload=ChartPayload.make(repo,Market.Frame.H1,true,true,true,false,true,true,1);JSONArray bands=payload.getJSONArray("bands");Set<String> ids=new HashSet<>();double nearRank=-1,farRank=-1;boolean role=false;for(int n=0;n<bands.length();n++){JSONObject b=bands.getJSONObject(n);check(ids.add(b.getString("id")),"No duplicate chart zone");if(b.getDouble("low")==4001){nearRank=b.getDouble("priority");role=b.getString("title").contains("R watch");}if(b.getDouble("low")==4040)farRank=b.getDouble("priority");}check(role&&nearRank>farRank,"Nearest watch is included, role-labelled and prioritised over distant repeated levels");count++;
   repo.analyses=Collections.singletonMap(Market.Frame.H1,new ZoneEngine.Analysis(Market.Frame.H1,bars,Collections.emptyList(),Collections.emptyList(),3,0,"range"));JSONArray ranges=ChartPayload.make(repo,Market.Frame.H1,true,true,false,false,false,true,1).getJSONArray("bands");check(ranges.length()>0&&ranges.getJSONObject(0).getString("title").contains("range"),"Observed range fallback agrees with native cards and is unconfirmed");count++;
   one.limited();check(repo.backoffRemaining()>0&&repo.streamStatus.equals("New stream subscribed"),"A late genuine 429 still protects the provider-wide budget without changing current stream status");count++;
   AtomicReference<MarketRepository> original=new AtomicReference<>();AtomicReference<android.app.Activity> shown=new AtomicReference<>();MonitorRuntime runtime=MonitorRuntime.get(c);
   try{
    i.runOnMainSync(()->{original.set(runtime.repository);runtime.repository.stop();runtime.repository=repo;});
    shown.set(i.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("alert-frame","H1")));
    AtomicReference<Throwable> failed=new AtomicReference<>();i.runOnMainSync(()->{try{
     MainActivity a=(MainActivity)shown.get();field(MainActivity.class,"page").setInt(a,1);Method render=MainActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(a);android.widget.LinearLayout panel=(android.widget.LinearLayout)field(MainActivity.class,"zonePanel").get(a);android.view.View before=panel.getChildAt(2);
     Map<Market.Frame,ZoneEngine.Analysis> copy=new EnumMap<>(Market.Frame.class);copy.putAll(repo.analyses);ZoneEngine.Analysis old=copy.get(Market.Frame.H1);copy.put(Market.Frame.H1,new ZoneEngine.Analysis(Market.Frame.H1,old.candles,old.zones,old.watchZones,old.atr,0,old.structure));repo.analyses=copy;runtime.refreshState();check(before==panel.getChildAt(2),"Unchanged zone refresh preserves actual card/focus targets");
    }catch(Throwable t){failed.set(t);}});if(failed.get()!=null)throw new AssertionError(failed.get());count++;
   }finally{i.runOnMainSync(()->{if(shown.get()!=null)shown.get().finish();});i.waitForIdleSync();i.runOnMainSync(()->{repo.stop();runtime.repository=original.get();runtime.refreshState();});}
  }finally{repo.stop();}
  return count;
 }
}
