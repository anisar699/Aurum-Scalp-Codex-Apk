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
        final ScheduledThreadPoolExecutor workers=new ScheduledThreadPoolExecutor(1);
        final ScheduledThreadPoolExecutor heartbeat=new ScheduledThreadPoolExecutor(1);
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
    public volatile Map<Market.Frame,Integer> requestedDepth=Collections.emptyMap();
    private volatile Set<Market.Frame> demand=EnumSet.allOf(Market.Frame.class);
    private final Map<Market.Frame,HistorySchedule> schedule=new EnumMap<>(Market.Frame.class);
    private final AtomicBoolean changeQueued=new AtomicBoolean();
    private static final Map<String,PollPolicy> PROVIDER_POLICIES=new ConcurrentHashMap<>();
    private final boolean streaming;
    public volatile String streamStatus="Not connected";
    public volatile long streamTicks, streamSubscriptions;
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
    void demand(Set<Market.Frame> frames){demand=frames.isEmpty()?Collections.emptySet():EnumSet.copyOf(frames);}
    String feedHolds(){
        long elapsed=SystemClock.elapsedRealtime(),now=marketNow();
        List<String> lines=new ArrayList<>();
        policy.activeHolds(elapsed).forEach((key,hold)->{
            String resource=key.equals(PollPolicy.REST)?"REST":key.equals(PollPolicy.STREAM)?"Stream reconnect":key;
            long wait=hold.until()-elapsed;
            String reason=hold.code()==429?"rate limited":"access refused";
            String until=now>0&&wait<30*86_400_000L?" · retry "+DataFreshness.date(now+wait)+" UTC":"";
            lines.add(resource+" · "+reason+" (HTTP "+hold.code()+") · "+((wait-1)/1000+1)+"s"+until);
        });
        return lines.isEmpty()?"No provider cooldown":String.join("\n",lines);
    }
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
        for(Market.Frame frame:Market.Frame.values())schedule.put(frame,new HistorySchedule());
        restoreHolds();
    }
    public String sourceName(){return "biquote · XAUUSD · MID analysis";}
    public void start(){
        synchronized(lock){
            if(session!=null&&session.active)return;
            Session s=new Session();
            session=s;
            streamStatus="Connecting";
            quoteOnline=false;
            s.workers.execute(()->{
                if(!loaded){
                    load(s);
                    synchronized(lock){if(current(s))loaded=true;}
                    notifyChange(s);
                }
            });
            s.quotes.scheduleWithFixedDelay(()->{
                ensureStream(s);
                Market.Quote q=quote;
                if(!s.subscribed||q==null||!quoteOnline||marketNow()-q.time>15_000)fetchQuote(s,false);
            },0,1,TimeUnit.SECONDS);
            if(streaming)s.heartbeat.scheduleWithFixedDelay(()->{
                BiquoteStream stream=s.stream;
                if(current(s)&&stream!=null)stream.maintain();
            },1,1,TimeUnit.SECONDS);
            for(Market.Frame f:Market.Frame.values()){
                s.workers.scheduleWithFixedDelay(()->{
                    if(demand.contains(f))fetchHistory(s,f,false);
                },f.ordinal(),1,TimeUnit.SECONDS);
            }
        }
    }

    public void stop(){
        Session s;
        synchronized(lock){
            s=session;
            session=null;
            streamStatus="Stopped";
            quoteOnline=false;
            if(s!=null){
                s.active=false;
                s.workers.shutdownNow();
                s.quotes.shutdownNow();
                s.heartbeat.shutdownNow();
                policy.releaseOwner(s);
            }
        }
        if(s!=null){
            if(s.stream!=null)s.stream.close();
            for(HttpURLConnection connection:s.connections)connection.disconnect();
        }
    }

    private void completeRequest(Session s,String key,long delay,FeedError error){
        long elapsed=SystemClock.elapsedRealtime();
        boolean persisted=false;
        synchronized(lock){
            if(error!=null&&error.status==429){
                policy.limit(PollPolicy.REST,elapsed,error.retryMs,429);
                persisted=true;
            }else if(error!=null&&(error.status==401||error.status==403)){
                delay=policy.refusal(key,elapsed,error.status,error.retryMs);
                persisted=true;
            }else if(error==null&&current(s)){
                policy.success(key);
            }
            if(current(s))policy.complete(key,elapsed,delay,false);
        }
        if(persisted)persistHolds();
    }

    private boolean current(Session s){return session==s&&s.active;}

    private boolean claimRequest(Session s,String resource,boolean manual){
        synchronized(lock){
            return current(s)&&policy.claim(resource,SystemClock.elapsedRealtime(),manual,s);
        }
    }

    private void notifyChange(Session s){
        if(!current(s)||!changeQueued.compareAndSet(false,true))return;
        main.postDelayed(()->{
            changeQueued.set(false);
            if(running())listener.changed();
        },250);
    }

    public boolean loading(){
        Session s=session;
        if(s==null)return false;
        if(s.quoteBusy.get())return true;
        for(AtomicBoolean b:s.busy.values())if(b.get())return true;
        return false;
    }

    public void refresh(Market.Frame visible){
        synchronized(lock){
            Session s=session;
            long now=SystemClock.elapsedRealtime();
            if(s==null||!s.active||now-lastManual<1000)return;
            lastManual=now;
            s.quotes.execute(()->fetchQuote(s,true));
            if(visible!=null)s.workers.execute(()->fetchHistory(s,visible,true));
        }
    }

    static int historyLimit(Market.Frame f){return f==Market.Frame.H1?1000:f==Market.Frame.H4?360:f==Market.Frame.M15?480:720;}
    private String endpoint(String interval,int limit){return "https://biquote.io/api/XAUUSD"+(interval.equals("TICK")?"":"/ohlc?interval="+interval+"&limit="+limit);}
    private boolean currentStream(Session s,long generation){return current(s)&&s.generation==generation;}
    private void marketState(Session s,boolean closed){
        boolean wasClosed=Boolean.TRUE.equals(s.providerClosed)||(quote!=null&&quote.marketClosed);
        s.providerClosed=closed;s.stateRevision++;s.stateAt=marketNow();
        if(quote!=null){Market.Quote q=quote;quote=new Market.Quote(q.time,q.bid,q.ask,closed,q.providerStale);}
        if(wasClosed&&!closed)policy.marketOpened();
    }
    private BiquoteStream.Listener streamListener(Session s,long generation){return new BiquoteStream.Listener(){
        public void date(String value){try{synchronized(lock){if(currentStream(s,generation))marketClock.sample(java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(),s.streamAttempt,SystemClock.elapsedRealtime());}}catch(Exception ignored){}}
        public void status(String value,boolean subscribed){synchronized(lock){if(!currentStream(s,generation))return;s.subscribed=subscribed;streamStatus=value;if(subscribed){streamSubscriptions++;policy.success(PollPolicy.STREAM);s.failures=0;s.nextStream=SystemClock.elapsedRealtime()+5000;}}notifyChange(s);}
        public void state(boolean closed){synchronized(lock){if(!currentStream(s,generation))return;marketState(s,closed);}notifyChange(s);}
        public void tick(JSONObject j){try{
            long now=marketNow();if(now==0)return;Market.Quote next=BiquoteCodec.quote(j,now);
            if(next.time>now+MarketClock.FUTURE_TOLERANCE)return;
            synchronized(lock){
                if(!currentStream(s,generation))return;streamTicks++;
                if(quote!=null&&quote.time<=now+MarketClock.FUTURE_TOLERANCE&&next.time<quote.time)return;
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
        public void limited(){
            policy.limit(PollPolicy.STREAM,SystemClock.elapsedRealtime(),60_000,429);
            persistHolds();
            notifyChange(s);
        }
        public void refused(int status){
            policy.refusal(PollPolicy.STREAM,SystemClock.elapsedRealtime(),status,0);
            persistHolds();
            notifyChange(s);
        }
    };}

    private void ensureStream(Session s){
        synchronized(lock){
            if(!streaming||!current(s))return;
            long elapsed=SystemClock.elapsedRealtime();
            // An established stream keeps sending ticks during a REST cooldown.
            if(s.stream!=null&&!s.stream.isClosed()){
                if(!s.subscribed&&elapsed-s.streamAttempt>20_000)s.stream.close();
                return;
            }
            if(elapsed<s.nextStream||!policy.claim(PollPolicy.STREAM,elapsed,false,s))return;
            s.streamAttempt=elapsed;
            s.nextStream=elapsed+Math.min(60_000,5000L<<Math.min(4,s.failures++));
            long generation=++s.generation;
            try{
                s.stream=new BiquoteStream(streamListener(s,generation));
                s.stream.connect();
            }catch(Exception failure){
                streamStatus="Stream unavailable; snapshot fallback";
            }finally{
                policy.complete(PollPolicy.STREAM,elapsed,5000,false);
            }
        }
    }

    static final class FeedError extends IOException {
        final long retryMs;
        final int status;
        FeedError(String message,long retry){this(message,retry,0);}
        FeedError(String message,long retry,boolean limited){this(message,retry,limited?429:0);}
        FeedError(String message,long retry,int status){super(message);retryMs=retry;this.status=status;}
    }

    static URL redirect(URL source,String location)throws IOException{
        if(location==null||location.trim().isEmpty())throw new IOException("Missing redirect target");
        URL target=new URL(source,location);
        if(!"https".equals(target.getProtocol())||!"biquote.io".equalsIgnoreCase(target.getHost())
            ||(target.getPort()!=-1&&target.getPort()!=443)||target.getUserInfo()!=null){
            throw new IOException("Redirect target outside the provider HTTPS origin");
        }
        return target;
    }

    private String get(String address)throws Exception{
        return get(new URL(address),true);
    }

    private String get(URL endpoint,boolean follow)throws Exception{
        HttpURLConnection c=(HttpURLConnection)endpoint.openConnection();
        c.setConnectTimeout(12_000);
        c.setReadTimeout(12_000);
        c.setUseCaches(false);
        Session owner=requestSession.get();
        synchronized(lock){
            if(owner==null||!current(owner))throw new IOException("Obsolete request");
            owner.connections.add(c);
        }
        c.setRequestProperty("User-Agent",BiquoteStream.USER_AGENT);
        c.setRequestProperty("Accept","application/json");
        c.setInstanceFollowRedirects(false);
        URL next=null;
        try{
            long started=SystemClock.elapsedRealtime();
            int status=c.getResponseCode();
            long received=SystemClock.elapsedRealtime(),date=c.getDate();
            synchronized(lock){
                if(current(owner)&&c.getHeaderFieldInt("Age",0)==0){
                    marketClock.sample(date,started,received);
                    responseTime.set(date);
                }
            }
            long retryBase=date>0?date:System.currentTimeMillis();
            if(status==429)throw new FeedError("Biquote rate limit (HTTP 429)",RetryGate.retryAfter(c.getHeaderField("Retry-After"),retryBase),429);
            if(status==401||status==403)throw new FeedError("Biquote refused access (HTTP "+status+")",
                RetryGate.retryAfter(c.getHeaderField("Retry-After"),retryBase,0),status);
            if(status==301||status==302||status==303||status==307||status==308){
                if(follow)try{next=redirect(endpoint,c.getHeaderField("Location"));}catch(IOException ignored){}
                if(next==null)throw new FeedError("Feed endpoint moved (HTTP "+status+")",600_000);
            }else{
                if(status!=200)throw new FeedError("Provider temporarily unavailable (HTTP "+status+")",30_000);
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] b=new byte[8192];
                    int n;
                    while((n=in.read(b))!=-1){
                        out.write(b,0,n);
                        if(out.size()>3_000_000)throw new FeedError("Invalid feed response size",60_000);
                    }
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            }
        }finally{
            owner.connections.remove(c);
            c.disconnect();
        }
        attempts.incrementAndGet();
        return get(next,false);
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
        if(!claimRequest(s,"quote",manual)){
            s.quoteBusy.set(false);
            return;
        }
        long delay=15_000;
        FeedError error=null;
        try{
            requestSession.set(s);
            long revision;
            synchronized(lock){revision=s.stateRevision;}
            attempts.incrementAndGet();
            String body=feed.get(endpoint("TICK",0));
            long now=marketNow();
            if(now==0)throw new IOException("Server time unavailable");
            JSONObject packet=new JSONObject(body);
            Market.Quote next=BiquoteCodec.quote(packet,now);
            if(next.time>now+MarketClock.FUTURE_TOLERANCE)throw new IOException("Quote time unverified");
            synchronized(lock){
                if(!current(s))return;
                Boolean stated=BiquoteCodec.marketState(packet);
                if(stated!=null&&s.stateRevision==revision)marketState(s,stated);
                boolean replace=quote==null||quote.time>now+MarketClock.FUTURE_TOLERANCE
                    ||next.time>quote.time||(next.time==quote.time&&(!s.subscribed||!quoteOnline));
                if(replace)quote=next;
                if(s.providerClosed!=null){
                    Market.Quote q=quote;
                    quote=new Market.Quote(q.time,q.bid,q.ask,s.providerClosed,q.providerStale);
                }
                if(replace||next.time==quote.time&&next.bid==quote.bid&&next.ask==quote.ask){
                    quoteOnline=true;
                    forming.tick(quote,true,now);
                }
                quoteError="";
                successes.incrementAndGet();
                delay=quote.marketClosed?60_000:15_000;
            }
            save(s,false);
        }catch(Exception e){
            error=failedRequest(e);
            synchronized(lock){if(current(s))quoteError=friendly(e);}
        }finally{
            completeRequest(s,"quote",error==null?delay:error.retryMs,error);
            requestSession.remove();
            responseTime.remove();
            s.quoteBusy.set(false);
            notifyChange(s);
        }
    }

    private void fetchHistory(Session s,Market.Frame frame,boolean manual){
        if(!current(s)||!s.busy.get(frame).compareAndSet(false,true))return;
        if(!claimRequest(s,frame.name(),manual)){
            s.busy.get(frame).set(false);
            return;
        }
        long delay=30_000;
        FeedError error=null;
        try{
            requestSession.set(s);
            long now=marketNow();
            List<Market.Candle> previous=candles.getOrDefault(frame,Collections.emptyList());
            ZoneEngine.Analysis existing=analyses.get(frame);
            HistorySchedule cadence=schedule.get(frame);
            int fullLimit=historyLimit(frame);
            int limit=cadence.requestLimit(frame,previous.size(),DataFreshness.lastClose(existing),now,fullLimit);
            attempts.incrementAndGet();
            String body=feed.get(endpoint(BiquoteCodec.interval(frame),limit));
            now=marketNow();
            if(now==0)throw new IOException("Server time unavailable; closed candles not recalculated");
            List<Market.Candle> incoming=BiquoteCodec.candles(body,frame,now,0);
            List<Market.Candle> next=BiquoteCodec.merge(previous,incoming,body,frame,fullLimit);
            boolean changed=existing==null||!HistoryQuality.sameBars(previous,next);
            ZoneEngine.Analysis analysis=changed?ZoneEngine.calculate(next,frame,now):existing;
            Market.Candle preview=BiquoteCodec.forming(body,frame,now);
            long latest=next.isEmpty()?0:next.get(next.size()-1).time;
            boolean confirmsLatest=incoming.stream().anyMatch(bar->bar.time==latest);
            synchronized(lock){
                if(!current(s))return;
                forming.confirm(frame,next);
                Long responseDate=responseTime.get();
                forming.seed(frame,preview,responseDate==null||responseDate<=0?now:responseDate);
                if(changed&&(!next.isEmpty()||existing!=null)){
                    candles=with(candles,frame,next);
                    analyses=with(analyses,frame,analysis);
                    queueCache(s);
                }
                providerDepth=with(providerDepth,frame,incoming.size());
                requestedDepth=with(requestedDepth,frame,limit);
                if(next.size()<HistorySchedule.MIN_BARS||!confirmsLatest){
                    liveSynced=without(liveSynced,frame);
                    if(next.size()<HistorySchedule.MIN_BARS)throw new FeedError("Limited history · "+next.size()+" confirmed "+frame.label+
                        " candles; need "+HistorySchedule.MIN_BARS,180_000);
                    throw new FeedError(incoming.isEmpty()?"Provider returned no closed candles":"Provider returned history older than the latest saved candle",30_000);
                }
                errors=without(errors,frame);
                long fetched=marketNow();
                synced=with(synced,frame,fetched);
                liveSynced=with(liveSynced,frame,fetched);
                successes.incrementAndGet();
                delay=cadence.afterResponse(frame,fetched,DataFreshness.lastClose(analysis),quote!=null&&quote.marketClosed,limit==fullLimit);
            }
        }catch(Exception e){
            error=failedRequest(e);
            synchronized(lock){
                if(current(s)){
                    Map<Market.Frame,String> err=new EnumMap<>(Market.Frame.class);
                    err.putAll(errors);
                    err.put(frame,friendly(e));
                    errors=Collections.unmodifiableMap(err);
                }
            }
        }finally{
            completeRequest(s,frame.name(),error==null?delay:error.retryMs,error);
            requestSession.remove();
            responseTime.remove();
            s.busy.get(frame).set(false);
            notifyChange(s);
        }
    }

    private static FeedError failedRequest(Exception error){
        return error instanceof FeedError feedError?feedError:new FeedError(friendly(error),30_000);
    }

    private void restoreHolds(){
        if(budget==null)return;
        long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
        boolean sameBoot=boot>=0&&budget.getInt("boot",-2)==boot;
        if(sameBoot){
            long legacy=budget.getLong("until",0)-elapsed;
            if(legacy>0)policy.limit(PollPolicy.REST,elapsed,legacy,429);
        }
        try{
            JSONObject saved=new JSONObject(budget.getString("holds","{}"));
            for(Iterator<String> keys=saved.keys();keys.hasNext();){
                String key=keys.next();
                JSONArray value=saved.getJSONArray(key);
                long remaining=sameBoot?value.getLong(0)-elapsed:value.getLong(1)-wall;
                int status=value.getInt(2);
                if(remaining>0)policy.limit(key,elapsed,remaining,status);
            }
        }catch(JSONException ignored){}
    }

    // A process restart cannot remove a cooldown persisted from a provider response.
    @android.annotation.SuppressLint("ApplySharedPref")
    private void persistHolds(){
        if(budget==null)return;
        synchronized(policy){
            long elapsed=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
            JSONObject saved=new JSONObject();
            try{
                for(var entry:policy.activeHolds(elapsed).entrySet()){
                    PollPolicy.Hold hold=entry.getValue();
                    saved.put(entry.getKey(),new JSONArray().put(hold.until())
                        .put(PollPolicy.deadline(wall,hold.until()-elapsed)).put(hold.code()));
                }
                budget.edit().putInt("boot",boot).remove("until").putString("holds",saved.toString()).commit();
            }catch(JSONException ignored){}
        }
    }
    private static String friendly(Exception e){
        if(e instanceof FeedError)return e.getMessage();if(e instanceof UnknownHostException)return "No internet connection";if(e instanceof SocketTimeoutException)return "Market-data connection timed out";if(e instanceof ConnectException)return "Could not connect to the price feed";if(e instanceof javax.net.ssl.SSLException)return "Secure connection could not be established";return "Could not refresh market data";
    }
    private static <T> Map<Market.Frame,T> with(Map<Market.Frame,T> source,Market.Frame frame,T next){
        Map<Market.Frame,T> copy=new EnumMap<>(Market.Frame.class);
        copy.putAll(source);copy.put(frame,next);return Collections.unmodifiableMap(copy);
    }
    private static <T> Map<Market.Frame,T> without(Map<Market.Frame,T> source,Market.Frame frame){
        if(!source.containsKey(frame))return source;
        Map<Market.Frame,T> copy=new EnumMap<>(Market.Frame.class);
        copy.putAll(source);copy.remove(frame);return Collections.unmodifiableMap(copy);
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
            for(Market.Frame f:Market.Frame.values())try{data.put(f,parseCandles(root.getJSONArray(f.name()).toString(),f,now,HistorySchedule.MIN_BARS));times.put(f,root.optLong(f.name()+"Synced"));}catch(Exception ignored){}
            Map<Market.Frame,ZoneEngine.Analysis> built=new EnumMap<>(Market.Frame.class);for(Map.Entry<Market.Frame,List<Market.Candle>> e:data.entrySet())built.put(e.getKey(),ZoneEngine.calculate(e.getValue(),e.getKey(),now));
            synchronized(lock){if(!current(s))return;if(quote==null)quote=stored;Map<Market.Frame,List<Market.Candle>> merged=new EnumMap<>(Market.Frame.class);merged.putAll(data);merged.putAll(candles);Map<Market.Frame,ZoneEngine.Analysis> all=new EnumMap<>(Market.Frame.class);all.putAll(built);all.putAll(analyses);Map<Market.Frame,Long> allTimes=new EnumMap<>(Market.Frame.class);allTimes.putAll(times);allTimes.putAll(synced);candles=Collections.unmodifiableMap(merged);analyses=Collections.unmodifiableMap(all);synced=Collections.unmodifiableMap(allTimes);}
        }catch(Exception ignored){}
    }
}
