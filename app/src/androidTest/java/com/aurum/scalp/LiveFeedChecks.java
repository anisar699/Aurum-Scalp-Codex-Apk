package com.aurum.scalp;
import android.app.Instrumentation;import android.content.*;import android.os.SystemClock;import java.io.*;import java.util.*;
/** Optional real Internet test, separate from deterministic regression tests. */
final class LiveFeedChecks {
 static int run(Instrumentation i,int soakSeconds)throws Exception{
  Context c=i.getTargetContext();File dir=new File(c.getCacheDir(),"live-biquote-"+System.nanoTime());if(!dir.mkdirs())throw new AssertionError("Test directory");
  Context isolated=new ContextWrapper(c){@Override public File getFilesDir(){return dir;}};
  // Old private data and settings must never configure or seed the replacement provider.
  java.nio.file.Files.write(new File(dir,"market-v2.json").toPath(),"{\"version\":3,\"quote\":[1767225600000,9999,10000]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  MarketRepository repo=new MarketRepository(isolated,()->{});try{
   long started=SystemClock.elapsedRealtime();repo.start();long end=started+90000;
   while(SystemClock.elapsedRealtime()<end&&(repo.quote==null||repo.analyses.size()<5||!repo.streamStatus.startsWith("Subscribed")))Thread.sleep(100);
   if(repo.quote==null||!repo.quoteOnline||!repo.timeTrusted()||repo.analyses.size()!=5||!repo.streamStatus.startsWith("Subscribed"))throw new AssertionError("Live biquote failed: "+repo.streamStatus+" / "+repo.quoteError+" / "+repo.errors+" frames="+repo.analyses.size());
   if(repo.quote.bid==9999)throw new AssertionError("Legacy cache leaked into replacement feed");
   StringBuilder report=new StringBuilder("PASS real Android biquote TLS REST + SignalR; source=").append(repo.sourceName()).append("; quote=").append(Market.quoteState(repo.quote,true,repo.marketNow())).append("; MID=").append(repo.quote.mid()).append("; stream=").append(repo.streamStatus).append("; startupAllFramesAndStreamMs=").append(SystemClock.elapsedRealtime()-started)
       .append("; initialQuoteAgeMs=").append(repo.marketNow()-repo.quote.time);
   for(Market.Frame f:Market.Frame.values()){ZoneEngine.Analysis a=repo.analyses.get(f);if(a.candles.size()<80)throw new AssertionError("Missing history");for(Market.Candle b:a.candles)if(Market.closeTime(b,f.duration())>repo.marketNow())throw new AssertionError("Future candle");report.append("; ").append(f.label).append("=").append(a.candles.size()).append(" closed / ").append(a.zones.size()).append(" zones / ").append(HistoryQuality.gaps(a.candles,f,a.candles.size())).append(" gaps");}
   if(soakSeconds>0){
    long began=SystemClock.elapsedRealtime(),last=began,maxGap=0,ticks=repo.streamTicks,subscriptions=repo.streamSubscriptions;
    long startTicks=ticks,requests=repo.attempts();
    boolean initialOpen=!repo.quote.marketClosed;
    while(SystemClock.elapsedRealtime()-began<1000L*soakSeconds){
     Thread.sleep(250);
     long current=repo.streamTicks,observed=SystemClock.elapsedRealtime();
     if(current>ticks){maxGap=Math.max(maxGap,observed-last);last=observed;ticks=current;}
    }
    maxGap=Math.max(maxGap,SystemClock.elapsedRealtime()-last);
    report.append("\nSample seconds=").append(soakSeconds).append("; startedOpen=").append(initialOpen)
      .append("; streamMessages=").append(ticks-startTicks).append("; additionalSubscriptions=").append(repo.streamSubscriptions-subscriptions)
      .append("; maxObservedMessageGapMs=").append(maxGap).append("; HTTP attempts=").append(repo.attempts()-requests)
      .append("; tailFrames=").append(repo.requestedDepth.entrySet().stream().filter(e->e.getValue()==HistorySchedule.TAIL_BARS).count());
   }
   java.nio.file.Files.write(new File(c.getExternalFilesDir(null),"live-biquote-result.txt").toPath(),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));return 9;
  }finally{repo.stop();}
 }
}
