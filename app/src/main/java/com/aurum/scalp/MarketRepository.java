package com.aurum.scalp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Biquote XAUUSD MID candles and matching bid/ask feed. Caches actual data only; session tokens discard obsolete responses. */
public final class MarketRepository {
    public interface Listener {void changed();}
    interface Feed {String get(String address)throws Exception;}
    private static final class Session {
        volatile boolean active=true;
        volatile BiquoteStream stream;
        volatile boolean subscribed;
        boolean cacheQueued;
        long streamAttempt,nextStream,generation,stateRevision,stateAt;int failures;Boolean providerClosed;
        final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();
        final ScheduledThreadPoolExecutor workers=new ScheduledThreadPoolExecutor(2);
        final AtomicBoolean quoteBusy=new AtomicBoolean();
        final Map<Market.Frame,AtomicBoolean> busy=new EnumMap<>(Market.Frame.class);
        final ScheduledThreadPoolExecutor quotes=new ScheduledThreadPoolExecutor(1);
        Session(){for(Market.Frame f:Market.Frame.values()){busy.put(f,new AtomicBoolean());}workers.setRemoveOnCancelPolicy(true);quotes.setRemoveOnCancelPolicy(true);}
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicFile cache;
    private final Listener listener;
    private final Feed feed;
    private final android.content.SharedPreferences budget;
    private final int boot;
    private final Object lock=new Object();
    private final ThreadLocal<Session> requestSession=new ThreadLocal<>();
    private final ThreadLocal<Long> responseTime=new ThreadLocal<>();
    private volatile Session session;
    private volatile boolean loaded;
    private long lastSave;
    private final Object diskLock = new Object();
    public volatile Map<Market.Frame,Integer> providerDepth=Collections.emptyMap();
    private static final Map<String,PollPolicy> PROVIDER_POLICIES=new ConcurrentHashMap<>();
    private final boolean streaming;
    public volatile String streamStatus="Not connected";
    public volatile long streamTicks;
    private final PollPolicy policy;
    private final MarketClock marketClock=new MarketClock();
    public long marketNow(){return marketClock.now(SystemClock.elapsedRealtime());}
    public boolean timeTrusted(){return marketNow()>0;}
    public final FormingBook forming=new FormingBook();
    private long lastManual;
    private final java.util.concurrent.atomic.AtomicLong attempts=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong successes=new java.util.concurrent.atomic.AtomicLong();
    public volatile Map<Market.Frame,Long> liveSynced=Collections.emptyMap();
    public long attempts(){return attempts.get();}
    public long successes(){return successes.get();}
    public long backoffRemaining(){return policy.remaining(SystemClock.elapsedRealtime());}
    public boolean running(){Session s=session;return s!=null&&s.active;}
    public volatile Market.Quote quote;
    public volatile boolean quoteOnline;
    public volatile String quoteError="";
    public volatile Map<Market.Frame,List<Market.Candle>> candles=Collections.emptyMap();
    public volatile Map<Market.Frame,ZoneEngine.Analysis> analyses=Collections.emptyMap();
    public volatile Map<Market.Frame,String> errors=Collections.emptyMap();
    public volatile Map<Market.Frame,Long> synced=Collections.emptyMap();
    public MarketRepository(Context context,Listener l){this(context,l,null);}
    MarketRepository(Context context,Listener l,Feed transport){
        streaming=transport==null;
        budget=transport==null?context.getSharedPreferences("biquote-budget-v1",Context.MODE_PRIVATE):null;
        boot=android.provider.Settings.Global.getInt(context.getContentResolver(),"boot_count",-1);listener=l;feed=transport==null?this::get:transport;
        policy=transport==null?PROVIDER_POLICIES.computeIfAbsent("biquote-mid",k->new PollPolicy()):new PollPolicy();
        cache=new AtomicFile(new File(context.getFilesDir(),"biquote-xauusd-mid-v1.json"));
        if(budget!=null&&boot>=0&&budget.getInt("boot",-2)==boot)policy.restore(budget.getLong("until",0),SystemClock.elapsedRealtime());
    }
    public String sourceName(){return "biquote · XAUUSD · MID analysis";}
    public void start(){
        synchronized(lock){
            if(session!=null&&session.active)return;Session s=new Session();session=s;streamStatus="Connecting";quoteOnline=false;liveSynced=Collections.emptyMap();providerDepth=Collections.emptyMap();
            s.workers.execute(()->{if(!loaded){load(s);synchronized(lock){if(current(s))loaded=true;}notifyChange(s);}});
            s.quotes.scheduleWithFixedDelay(()->{ensureStream(s);Market.Quote q=quote;if(!s.subscribed||q==null||!quoteOnline||marketNow()-q.time>15_000)fetchQuote(s,false);},0,1,TimeUnit.SECONDS);
            for(Market.Frame f:Market.Frame.values())s.workers.scheduleWithFixedDelay(()->fetchHistory(s,f,false),f.ordinal(),1,TimeUnit.SECONDS);
        }
    }
    public void stop(){Session s;synchronized(lock){s=session;session=null;streamStatus="Stopped";quoteOnline=false;liveSynced=Collections.emptyMap();providerDepth=Collections.emptyMap();if(s!=null){s.active=false;s.workers.shutdownNow();s.quotes.shutdownNow();policy.releaseOwner(s);}}if(s!=null){if(s.stream!=null)s.stream.close();for(HttpURLConnection c:s.connections)c.disconnect();}}
    private void completeRequest(Session s,String key,long delay,boolean limited){synchronized(lock){if(current(s))policy.complete(key,SystemClock.elapsedRealtime(),delay,limited);else if(limited)policy.restore(SystemClock.elapsedRealtime()+Math.min(PollPolicy.MAX_RETRY,delay),SystemClock.elapsedRealtime());}persistLimit(limited);}
    private boolean current(Session s){return session==s&&s.active;}
    private void notifyChange(Session s){if(current(s))main.post(()->{if(current(s))listener.changed();});}
    public boolean loading(){Session s=session;if(s==null)return false;if(s.quoteBusy.get())return true;for(AtomicBoolean b:s.busy.values())if(b.get())return true;return false;}
    public void refresh(){synchronized(lock){Session s=session;long now=SystemClock.elapsedRealtime();if(s==null||!s.active||now-lastManual<1000)return;lastManual=now;try{s.quotes.execute(()->fetchQuote(s,true));for(Market.Frame f:Market.Frame.values())s.workers.execute(()->fetchHistory(s,f,true));}catch(RejectedExecutionException ignored){}}}
    static int historyLimit(Market.Frame f){return f==Market.Frame.H1?1000:f==Market.Frame.H4?360:f==Market.Frame.M15?480:720;}
    private String endpoint(String interval,int limit,long now){return "https://biquote.io/api/XAUUSD"+(interval.equals("TICK")?"":"/ohlc?interval="+interval+"&limit="+limit);}
    private boolean currentStream(Session s,long generation){return current(s)&&s.generation==generation;}
    private void marketState(Session s,boolean closed){
        boolean wasClosed=Boolean.TRUE.equals(s.providerClosed)||(quote!=null&&quote.marketClosed);
        s.providerClosed=closed;s.stateRevision++;s.stateAt=marketNow();
        if(quote!=null){Market.Quote q=quote;quote=new Market.Quote(q.time,q.bid,q.ask,closed,q.providerStale);}
        if(wasClosed&&!closed)policy.marketOpened();
    }
    private BiquoteStream.Listener streamListener(Session s,long generation){return new BiquoteStream.Listener(){
        public void date(String value){try{synchronized(lock){if(currentStream(s,generation))marketClock.sync(java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(),SystemClock.elapsedRealtime());}}catch(Exception ignored){}}
        public void status(String value,boolean subscribed){synchronized(lock){if(!currentStream(s,generation))return;s.subscribed=subscribed;streamStatus=value;if(subscribed){s.failures=0;s.nextStream=SystemClock.elapsedRealtime()+5000;}}notifyChange(s);}
        public void state(boolean closed){synchronized(lock){if(!currentStream(s,generation))return;marketState(s,closed);}notifyChange(s);}
        public void tick(JSONObject j){try{
            long now=marketNow();if(now==0)return;Market.Quote next=BiquoteCodec.quote(j,now);
            synchronized(lock){
                if(!currentStream(s,generation))return;streamTicks++;
                if(quote!=null&&quote.time<=now+1000&&next.time<quote.time)return;
                boolean wasClosed=quote!=null&&quote.marketClosed;
                Boolean stated=BiquoteCodec.marketState(j);if(stated!=null)marketState(s,stated);
                // A subscription snapshot must not freeze Friday's closed state after trading resumes.
                if(stated==null&&Boolean.TRUE.equals(s.providerClosed)&&s.stateAt>0&&next.time>s.stateAt+30_000&&!next.providerStale&&now-next.time<=30_000)marketState(s,false);
                boolean closed=s.providerClosed!=null?s.providerClosed:next.marketClosed||(wasClosed&&now-next.time>30_000);
                quote=new Market.Quote(next.time,next.bid,next.ask,closed,next.providerStale);
                // An identical provider quote is still proof of reception in this connection.
                quoteOnline=true;quoteError="";forming.tick(quote,true,now);
                if(wasClosed&&!closed)policy.marketOpened();
            }save(s,false);notifyChange(s);
        }catch(Exception ignored){}}
        public void limited(){synchronized(lock){long now=SystemClock.elapsedRealtime();if(currentStream(s,generation))policy.complete("stream",now,60_000,true);else policy.restore(now+60_000,now);}persistLimit(true);notifyChange(s);}
    };}
    private void ensureStream(Session s){
        synchronized(lock){
            if(!streaming||!current(s))return;long elapsed=SystemClock.elapsedRealtime();
            if(policy.remaining(elapsed)>0){if(s.stream!=null)s.stream.close();return;}
            if(s.stream!=null&&!s.stream.isClosed()){if(!s.subscribed&&elapsed-s.streamAttempt>20_000)s.stream.close();return;}
            if(elapsed<s.nextStream||!policy.claim("stream",elapsed,false,s))return;
            s.streamAttempt=elapsed;s.nextStream=elapsed+Math.min(60_000,5000L<<Math.min(4,s.failures++));long generation=++s.generation;
            try{s.stream=new BiquoteStream(streamListener(s,generation));s.stream.connect();}
            catch(Exception failure){streamStatus="Stream unavailable; snapshot fallback";}
            finally{completeRequest(s,"stream",5000,false);}
        }
    }
    static final class FeedError extends IOException {
        final long retryMs;
        final boolean rateLimited;
        FeedError(String message,long retry){this(message,retry,false);}
        FeedError(String message,long retry,boolean limited){super(message);retryMs=retry;rateLimited=limited;}
    }
    private String get(String address)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(12_000);c.setReadTimeout(12_000);c.setUseCaches(false);
        Session owner=requestSession.get();synchronized(lock){if(owner==null||!current(owner))throw new IOException("Obsolete request");owner.connections.add(c);}
        c.setRequestProperty("User-Agent","AurumScalp/2.8 (Android; XAUUSD viewer)");c.setRequestProperty("Accept","application/json");
        c.setInstanceFollowRedirects(false);
        try{
            int status=c.getResponseCode();
            synchronized(lock){if(current(owner)){marketClock.sync(c.getDate(),SystemClock.elapsedRealtime());responseTime.set(c.getDate());}}
            if(status!=200){if(status==401||status==403)throw new FeedError("biquote access denied; waiting before retry",3600000,true);if(status==429)throw new FeedError("Provider rate limit; waiting before retry",RetryGate.retryAfter(c.getHeaderField("Retry-After"),System.currentTimeMillis()),true);throw new FeedError("Provider temporarily unavailable ("+status+")",30_000);}
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>3_000_000)throw new FeedError("Invalid feed response size",60_000);}return out.toString(StandardCharsets.UTF_8.name());
            }
        }finally{owner.connections.remove(c);c.disconnect();}
    }
    static JSONArray unwrap(String input)throws Exception{
        String s=input.trim();if(!s.startsWith("["))throw new IOException("Unexpected saved data");
        JSONArray a=new JSONArray(s);if(a.length()==0||a.isNull(0))throw new IOException("Provider returned no data");return a;
    }
    static List<Market.Candle> parseCandles(String body,Market.Frame frame,long now,int minimum)throws Exception{
        JSONArray a=unwrap(body);List<Market.Candle> raw=new ArrayList<>();
        for(int i=0;i<a.length();i++){JSONArray r=a.optJSONArray(i);if(r==null||r.length()<5)continue;raw.add(new Market.Candle(r.optLong(0),r.optDouble(1,Double.NaN),r.optDouble(2,Double.NaN),r.optDouble(3,Double.NaN),r.optDouble(4,Double.NaN),r.optLong(5)>r.optLong(0)?r.optLong(5):0));}
        List<Market.Candle> clean=Market.closed(raw,frame.duration(),now);if(clean.size()<minimum)throw new IOException("Insufficient valid "+frame.label+" history");return clean;
    }
    private void fetchQuote(Session s,boolean manual){
        if(!current(s)||!s.quoteBusy.compareAndSet(false,true))return;
        if(!policy.claim("quote",SystemClock.elapsedRealtime(),manual,s)){s.quoteBusy.set(false);return;}
        long delay=15_000;boolean limited=false,quoteOk=false;
        try{
            requestSession.set(s);long revision;synchronized(lock){revision=s.stateRevision;}attempts.incrementAndGet();String body=feed.get(endpoint("TICK",3,System.currentTimeMillis()));long now=marketNow();if(now==0)throw new IOException("Server time unavailable");Market.Quote next=BiquoteCodec.quote(body,now);if(next.marketClosed)delay=60_000;
            synchronized(lock){if(!current(s))return;Boolean stated=BiquoteCodec.marketState(new JSONObject(body));if(stated!=null&&s.stateRevision==revision)marketState(s,stated);if(quote==null||quote.time>now+1000||(next.time>quote.time||(next.time==quote.time&&!s.subscribed)))quote=next;if(s.providerClosed!=null){Market.Quote q=quote;quote=new Market.Quote(q.time,q.bid,q.ask,s.providerClosed,q.providerStale);}quoteOnline=true;forming.tick(quote,true,now);quoteError="";successes.incrementAndGet();quoteOk=true;}save(s,false);
        }catch(Exception e){delay=e instanceof FeedError?((FeedError)e).retryMs:30_000;limited=e instanceof FeedError&&((FeedError)e).rateLimited;synchronized(lock){if(current(s))quoteError=friendly(e);}}
        finally{synchronized(lock){if(quoteOk)delay=quote!=null&&quote.marketClosed?60_000:15_000;completeRequest(s,"quote",delay,limited);}requestSession.remove();responseTime.remove();s.quoteBusy.set(false);notifyChange(s);}
    }
    private void fetchHistory(Session s,Market.Frame frame,boolean manual){
        if(!current(s)||!s.busy.get(frame).compareAndSet(false,true))return;
        if(!policy.claim(frame.name(),SystemClock.elapsedRealtime(),manual,s)){s.busy.get(frame).set(false);return;}
        long delay=frame==Market.Frame.M1?30_000:frame.scalp()?60_000:180_000;boolean limited=false,historyOk=false;
        try{
            requestSession.set(s);long now=marketNow()>0?marketNow():System.currentTimeMillis();String interval=BiquoteCodec.interval(frame);
            attempts.incrementAndGet();String body=feed.get(endpoint(interval,historyLimit(frame),now));now=marketNow();if(now==0)throw new IOException("Server time unavailable; closed candles not recalculated");
            List<Market.Candle> incoming=BiquoteCodec.candles(body,frame,now,80);
            List<Market.Candle> previous=candles.getOrDefault(frame,Collections.emptyList());
            List<Market.Candle> next=BiquoteCodec.merge(previous,incoming,body,frame,historyLimit(frame));
            ZoneEngine.Analysis existing=analyses.get(frame);
            boolean changed=existing==null || !HistoryQuality.sameBars(previous,next);
            ZoneEngine.Analysis analysis=changed?ZoneEngine.calculate(next,frame,now):existing;
            Market.Candle preview=BiquoteCodec.forming(body,frame,now);
            synchronized(lock){
                if(!current(s))return;
                forming.confirm(frame,next);
                Long responseDate=responseTime.get();
                forming.seed(frame,preview,responseDate==null||responseDate<=0?now:responseDate);
                if(changed){candles=with(candles,frame,next);analyses=with(analyses,frame,analysis);}
                long fetched=marketNow();
                Map<Market.Frame,String> clean=new EnumMap<>(Market.Frame.class);clean.putAll(errors);clean.remove(frame);
                errors=Collections.unmodifiableMap(clean);
                synced=with(synced,frame,fetched);
                liveSynced=with(liveSynced,frame,fetched);
                providerDepth=with(providerDepth,frame,incoming.size());
                successes.incrementAndGet();historyOk=true;
                if(changed)queueCache(s);
            }
        }catch(Exception e){delay=e instanceof FeedError?((FeedError)e).retryMs:30_000;limited=e instanceof FeedError&&((FeedError)e).rateLimited;synchronized(lock){if(current(s)){Map<Market.Frame,String> err=new EnumMap<>(Market.Frame.class);err.putAll(errors);err.put(frame,friendly(e));errors=Collections.unmodifiableMap(err);}}}
        finally{synchronized(lock){if(historyOk)delay=quote!=null&&quote.marketClosed?600_000:frame==Market.Frame.M1?30_000:frame.scalp()?60_000:180_000;completeRequest(s,frame.name(),delay,limited);}requestSession.remove();responseTime.remove();s.busy.get(frame).set(false);notifyChange(s);}
    }
    // Worker-only synchronous write intentionally survives immediate process death after 429.
    @android.annotation.SuppressLint("ApplySharedPref")
    private void persistLimit(boolean limited){if(limited&&budget!=null&&boot>=0){synchronized(policy){long now=SystemClock.elapsedRealtime();budget.edit().putInt("boot",boot).putLong("until",now+policy.remaining(now)).commit();}}}
    private static String friendly(Exception e){
        if(e instanceof FeedError)return e.getMessage();if(e instanceof UnknownHostException)return "No internet connection";if(e instanceof SocketTimeoutException)return "Market-data connection timed out";if(e instanceof ConnectException)return "Could not connect to the price feed";if(e instanceof javax.net.ssl.SSLException)return "Secure connection could not be established";return "Could not refresh market data";
    }
    private static <T> Map<Market.Frame,T> with(Map<Market.Frame,T> source,Market.Frame frame,T next){
        Map<Market.Frame,T> copy=new EnumMap<>(Market.Frame.class);
        copy.putAll(source);copy.put(frame,next);return Collections.unmodifiableMap(copy);
    }
    private void queueCache(Session s){
        if(s.cacheQueued)return;
        s.cacheQueued=true;
        s.workers.schedule(()->{
            synchronized(lock){s.cacheQueued=false;}
            save(s,true);
        },3,TimeUnit.SECONDS);
    }
    private void save(Session s,boolean force){
        synchronized(diskLock){
            long now=SystemClock.elapsedRealtime();
            long referenceTime;
            Map<Market.Frame,List<Market.Candle>> data;
            Map<Market.Frame,Long> times;
            Market.Quote savedQuote;
            synchronized(lock){
                if(!current(s)||!loaded||(!force&&lastSave>0&&now-lastSave<300_000))return;
                referenceTime=marketNow();data=candles;times=synced;savedQuote=quote;
            }
            FileOutputStream output=null;
            try{
                JSONObject root=new JSONObject().put("version",4).put("provider","biquote").put("basis","MID").put("marketTime",referenceTime);
                if(savedQuote!=null)root.put("quote",new JSONArray().put(savedQuote.time).put(savedQuote.bid).put(savedQuote.ask).put(savedQuote.marketClosed).put(savedQuote.providerStale));
                for(Market.Frame f:Market.Frame.values()){
                    JSONArray rows=new JSONArray();
                    for(Market.Candle c:data.getOrDefault(f,Collections.emptyList()))
                        rows.put(new JSONArray().put(c.time).put(c.open).put(c.high).put(c.low).put(c.close).put(c.endTime));
                    root.put(f.name(),rows).put(f.name()+"Synced",times.getOrDefault(f,0L));
                }
                if(!current(s))return;
                output=cache.startWrite();output.write(root.toString().getBytes(StandardCharsets.UTF_8));
                cache.finishWrite(output);lastSave=now;
            }catch(Exception ignored){if(output!=null)cache.failWrite(output);}
        }
    }
    private InputStream openCache()throws IOException{synchronized(diskLock){return cache.openRead();}}
    private void load(Session s){
        try(InputStream in=openCache();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>3_000_000)return;}
            JSONObject root=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));if(root.optInt("version")!=4||!"biquote".equals(root.optString("provider"))||!"MID".equals(root.optString("basis")))return;long now=root.optLong("marketTime",0);
            Market.Quote stored=null;if(root.has("quote"))try{JSONArray q=root.getJSONArray("quote");stored=new Market.Quote(q.getLong(0),q.getDouble(1),q.getDouble(2),q.optBoolean(3,false),q.optBoolean(4,false));if(!stored.valid())stored=null;}catch(Exception ignored){}
            Map<Market.Frame,List<Market.Candle>> data=new EnumMap<>(Market.Frame.class);Map<Market.Frame,Long> times=new EnumMap<>(Market.Frame.class);
            for(Market.Frame f:Market.Frame.values())try{data.put(f,parseCandles(root.getJSONArray(f.name()).toString(),f,now,80));times.put(f,root.optLong(f.name()+"Synced"));}catch(Exception ignored){}
            Map<Market.Frame,ZoneEngine.Analysis> built=new EnumMap<>(Market.Frame.class);for(Map.Entry<Market.Frame,List<Market.Candle>> e:data.entrySet())built.put(e.getKey(),ZoneEngine.calculate(e.getValue(),e.getKey(),now));
            synchronized(lock){if(!current(s))return;if(quote==null)quote=stored;Map<Market.Frame,List<Market.Candle>> merged=new EnumMap<>(Market.Frame.class);merged.putAll(data);merged.putAll(candles);Map<Market.Frame,ZoneEngine.Analysis> all=new EnumMap<>(Market.Frame.class);all.putAll(built);all.putAll(analyses);Map<Market.Frame,Long> allTimes=new EnumMap<>(Market.Frame.class);allTimes.putAll(times);allTimes.putAll(synced);candles=Collections.unmodifiableMap(merged);analyses=Collections.unmodifiableMap(all);synced=Collections.unmodifiableMap(allTimes);}
        }catch(Exception ignored){}
    }
}
