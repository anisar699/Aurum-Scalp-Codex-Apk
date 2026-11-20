package com.aurum.scalp;
import org.json.*;import java.util.*;

final class ChartPayload {
    static JSONObject make(MarketRepository repo,Market.Frame f,boolean history,boolean zones,boolean context,boolean ema,boolean live,boolean near,float font)throws JSONException{
        ZoneEngine.Analysis a=repo.analyses.get(f);long now=repo.marketNow();Market.Quote q=repo.quote;double price=Market.referencePrice(q,repo.analyses.values());
        String state=Market.priceState(q,repo.quoteOnline,now,repo.analyses.values()).replace("● ","");
        JSONObject out=new JSONObject().put("frame",f.label).put("frameKey",f.name()).put("now",now/1000).put("price",price).put("state",state)
            .put("referenceLabel",state.equals("CANDLE REFERENCE")?"MID close":"MID")
            .put("zonesOn",zones).put("contextOn",context).put("emaOn",ema).put("liveOn",live).put("nearOn",near).put("font",font).put("atr",a==null?0:a.atr);
        int n=a==null?0:a.candles.size();out.put("count",n).put("limited",n<f.lookback+f.wing*2).put("historyFresh",a!=null&&a.historyFresh(now)).put("historyStatus",DataFreshness.summary(a,now,repo.liveSynced.getOrDefault(f,0L),q));
        if(history){JSONArray bars=new JSONArray();if(a!=null)for(Market.Candle c:a.candles)bars.put(row(c,"closed"));out.put("history",bars);}
        JSONArray pending=new JSONArray();long last=n==0?0:a.candles.get(n-1).time;
        for(FormingBook.Bar b:repo.forming.visible(f,last,now,q,repo.quoteOnline))pending.put(row(b.candle,now>=b.candle.endTime?"pending":b.partial?"partial":"forming"));out.put("forming",pending);
        JSONArray bands=new JSONArray();if(a!=null)append(bands,a,price,false,now,repo.liveSynced,q);
        for(Market.Frame macro:new Market.Frame[]{Market.Frame.H1,Market.Frame.H4})if(macro!=f){ZoneEngine.Analysis other=repo.analyses.get(macro);if(other!=null)append(bands,other,price,true,now,repo.liveSynced,q);}
        return out.put("bands",bands);
    }
    private static JSONArray row(Market.Candle c,String kind)throws JSONException{return new JSONArray().put(c.time/1000).put(c.open).put(c.high).put(c.low).put(c.close).put(kind);}
    private static void append(JSONArray out,ZoneEngine.Analysis a,double price,boolean macro,long now,Map<Market.Frame,Long> synced,Market.Quote quote)throws JSONException{
        for(boolean side:new boolean[]{true,false}){
            List<ZoneEngine.Zone> candidates=a.nearest(price,side,macro?2:4,false);candidates.addAll(ZoneSelection.watches(a,price,side,macro?1:2,ZoneSelection.Distance.ALL));Map<String,ZoneEngine.Zone> unique=new LinkedHashMap<>();for(ZoneEngine.Zone z:candidates)unique.put(z.id+":"+z.support,z);List<ZoneEngine.Zone> list=new ArrayList<>(unique.values());
            for(ZoneEngine.Zone z:list)out.put(new JSONObject().put("id",z.id+":"+z.support+":"+macro).put("low",z.low).put("high",z.high).put("price",z.center).put("support",z.support).put("watch",z.reference).put("macro",macro).put("title",a.frame.label+" "+(z.support?"S":"R")+(z.reference?(z.breakPending?" pending":z.retestWatch?" watch":" range"):"")).put("tests",z.tests).put("defendedVisits",z.defendedVisits).put("failedVisits",z.failedVisits)
                .put("pendingBreak",z.breakPending).put("rejectionAtr",z.rejectionAtr).put("reaction",DataFreshness.date(z.lastTest)).put("freshness",DataFreshness.summary(a,now,synced.getOrDefault(a.frame,0L),quote)).put("priority",(macro?0:1000)+100/(1+z.distance(price)/Math.max(.01,a.atr))));
        }
    }
}
