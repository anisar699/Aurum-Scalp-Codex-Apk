package com.aurum.scalp;

import org.json.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Biquote's JSON contracts. Candles are treated as MID data, never relabelled as bid. */
final class BiquoteCodec {
    static long timestamp(String value)throws IOException{try{return Instant.parse(value).toEpochMilli();}catch(Exception e){throw new IOException("Invalid provider timestamp");}}
    static Boolean marketState(JSONObject j){String value=j.optString("marketState");return "closed".equals(value)?Boolean.TRUE:"open".equals(value)?Boolean.FALSE:null;}
    static Market.Quote quote(String body,long now)throws Exception{return quote(new JSONObject(body),now);}
    static Market.Quote quote(JSONObject j,long now)throws Exception{
        if(!"XAUUSD".equals(j.optString("symbol")))throw new IOException("Wrong quote symbol");
        Market.Quote q=new Market.Quote(timestamp(j.getString("timestamp")),j.optDouble("bid",Double.NaN),j.optDouble("ask",Double.NaN),"closed".equals(j.optString("marketState")),j.optBoolean("stale",false));
        if(!q.valid()||q.time>now+60_000)throw new IOException("Invalid XAUUSD quote");return q;
    }
    static List<Market.Candle> candles(String body,Market.Frame frame,long now,int minimum)throws Exception{
        JSONObject j=new JSONObject(body);validateSeries(j,frame);JSONArray rows=j.getJSONArray("bars");List<Market.Candle> raw=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject b=rows.optJSONObject(i);if(b==null||!Boolean.FALSE.equals(b.opt("isOpen")))continue;
            long t;try{t=timestamp(b.getString("openTime"));}catch(Exception bad){continue;}
            // This API publishes UTC aggregation buckets. A different alignment requires an explicit adapter.
            if(t%frame.duration()!=0)continue;
            Market.Candle c=new Market.Candle(t,b.optDouble("open",Double.NaN),b.optDouble("high",Double.NaN),b.optDouble("low",Double.NaN),b.optDouble("close",Double.NaN),t+frame.duration());
            if(c.valid()&&c.endTime<=now)raw.add(c);
        }
        List<Market.Candle> result=Market.closed(raw,frame.duration(),now);
        if(result.size()<minimum)throw new IOException("Need "+minimum+" confirmed "+frame.label+" candles; provider supplied "+result.size());return result;
    }
    static Market.Candle forming(String body,Market.Frame f,long now)throws Exception{
        JSONObject j=new JSONObject(body);validateSeries(j,f);JSONArray rows=j.getJSONArray("bars");Market.Candle best=null;
        for(int i=0;i<rows.length();i++){
            JSONObject b=rows.optJSONObject(i);if(b==null||!Boolean.TRUE.equals(b.opt("isOpen")))continue;
            try{long t=timestamp(b.getString("openTime"));Market.Candle c=new Market.Candle(t,b.optDouble("open",Double.NaN),b.optDouble("high",Double.NaN),b.optDouble("low",Double.NaN),b.optDouble("close",Double.NaN),t+f.duration());
                if(c.valid()&&t%f.duration()==0&&t<=now&&now<c.endTime&&(best==null||t>best.time))best=c;
            }catch(Exception ignored){}
        }return best;
    }
    static String interval(Market.Frame f){return f==Market.Frame.H1?"1h":f==Market.Frame.H4?"4h":f.label;}
    private static void validateSeries(JSONObject j,Market.Frame f)throws IOException{if(!"XAUUSD".equals(j.optString("symbol"))||!interval(f).equals(j.optString("interval")))throw new IOException("Wrong candle series");}
    static List<Market.Candle> merge(List<Market.Candle> old,List<Market.Candle> incoming,String body,Market.Frame f,int limit)throws Exception{
        TreeMap<Long,Market.Candle> map=new TreeMap<>();for(Market.Candle c:old)map.put(c.time,c);for(Market.Candle c:incoming)map.put(c.time,c);
        JSONObject j=new JSONObject(body);validateSeries(j,f);JSONArray rows=j.getJSONArray("bars");
        for(int i=0;i<rows.length();i++){JSONObject b=rows.optJSONObject(i);if(b!=null&&Boolean.TRUE.equals(b.opt("isOpen")))try{map.remove(timestamp(b.getString("openTime")));}catch(Exception ignored){}}
        while(map.size()>limit)map.pollFirstEntry();return Collections.unmodifiableList(new ArrayList<>(map.values()));
    }
}
