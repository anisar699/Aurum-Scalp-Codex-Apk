package com.aurum.scalp;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.view.MotionEvent;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Deterministic transport/lifecycle scenarios; no test prices are shipped in the app. */
final class AuditChecks {
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private static void await(BooleanSupplier predicate,String message)throws Exception{
        long end=System.currentTimeMillis()+12_000;while(!predicate.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(25);check(predicate.getAsBoolean(),message);
    }
    private static String quote(double bid){return "{\"symbol\":\"XAUUSD\",\"timestamp\":\""+java.time.Instant.ofEpochMilli(System.currentTimeMillis()-1000)+"\",\"bid\":"+bid+",\"ask\":"+(bid+.5)+"}";}
    private static Context isolated(Instrumentation i,String name){
        File dir=new File(i.getTargetContext().getCacheDir(),"audit-"+name+"-"+System.nanoTime());check(dir.mkdirs(),"Test directory");
        return new ContextWrapper(i.getTargetContext()){@Override public File getFilesDir(){return dir;}};
    }
    static int run(Instrumentation instrumentation)throws Exception{
        int checks=0;
        AtomicInteger mode=new AtomicInteger(),requests=new AtomicInteger();
        MarketRepository repo=new MarketRepository(isolated(instrumentation,"status"),()->{},address->{
            if(!address.endsWith("/api/XAUUSD"))throw new IOException("controlled history failure");
            requests.incrementAndGet();if(mode.get()==1)throw new IOException("controlled network failure");if(mode.get()==2)throw new MarketRepository.FeedError("controlled rate limit",60_000,true);return quote(4000);
        });
        try{
            trusted(repo);repo.start();await(()->repo.quoteOnline&&!repo.loading(),"Initial quote connection");checks++;
            mode.set(1);Thread.sleep(10_100);repo.refresh(null);await(()->!repo.quoteError.isEmpty()&&!repo.loading(),"Transient tick failure");check(repo.quoteOnline,"Failed tick must retain current-session connection history");checks++;
            int beforeOrdinary=requests.get();Thread.sleep(10_100);repo.refresh(null);await(()->requests.get()>beforeOrdinary&&!repo.loading(),"Manual retry clears ordinary network backoff");checks++;
            mode.set(2);int threshold=requests.get();Thread.sleep(10_100);repo.refresh(null);await(()->requests.get()>threshold&&!repo.loading(),"429 received");int beforeRate=requests.get();Thread.sleep(10_100);repo.refresh(null);Thread.sleep(300);check(requests.get()==beforeRate,"Manual refresh must preserve the exact 60-second 429 backoff");checks++;
            repo.stop();check(!repo.quoteOnline,"New foreground session must require a fresh connection");checks++;
        }finally{repo.stop();}

        Context cached=isolated(instrumentation,"cache");File cache=new File(cached.getFilesDir(),"biquote-xauusd-mid-v1.json");
        JSONObject seed=new JSONObject().put("version",4).put("provider","biquote").put("basis","MID").put("quote",new JSONArray().put(System.currentTimeMillis()-1000).put(4010).put(4010.5)).put("M5","invalid retained entry").put("marker","must remain byte-identical until a successful network response");
        byte[] original=seed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(cache.toPath(),original);
        MarketRepository offline=new MarketRepository(cached,()->{},address->{throw new IOException("controlled offline");});
        try{trusted(offline);offline.start();await(()->offline.quote!=null&&!offline.loading(),"Cached quote loads offline");Thread.sleep(150);check(Arrays.equals(original,Files.readAllBytes(cache.toPath())),"Cold cache load must not rewrite or erase rejected timeframes");check(!offline.quoteOnline,"Cached quote is SAVED");checks+=2;}finally{offline.stop();}

        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        MarketRepository lifecycle=new MarketRepository(isolated(instrumentation,"lifecycle"),()->{},address->{
            if(!address.endsWith("/api/XAUUSD"))throw new IOException("controlled history failure");
            if(calls.getAndIncrement()==0){entered.countDown();boolean waiting=true;while(waiting)try{release.await();waiting=false;}catch(InterruptedException ignored){}return quote(3900);}
            return quote(4200);
        });
        try{
            trusted(lifecycle);lifecycle.start();check(entered.await(12,TimeUnit.SECONDS),"Old session request begins");lifecycle.stop();trusted(lifecycle);lifecycle.start();await(()->lifecycle.quote!=null&&lifecycle.quote.bid==4200,"New session quote succeeds");release.countDown();Thread.sleep(250);check(lifecycle.quote.bid==4200,"Late obsolete response cannot overwrite current session");checks++;
        }finally{release.countDown();lifecycle.stop();}

        AtomicReference<Throwable> uiFailure=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{try{
            Market.Frame f=Market.Frame.M5;long t=1_767_225_600_000L;List<Market.Candle> b=new ArrayList<>();
            for(int n=0;n<120;n++){double c=4000+3*Math.sin(n*.5);b.add(new Market.Candle(t+n*f.duration(),c,c+1,c-1,c));}
            CandleChart chart=new CandleChart(instrumentation.getTargetContext());chart.layout(0,0,720,470);chart.data(ZoneEngine.calculate(b,f,t+120*f.duration()),4001.25);
            Bitmap bitmap=Bitmap.createBitmap(720,470,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);chart.draw(canvas);
            touch(chart,MotionEvent.ACTION_DOWN,300,220);touch(chart,MotionEvent.ACTION_UP,300,220);String selected=chart.getContentDescription().toString();check(selected.startsWith("Selected candle"),"Candle selected");
            b.remove(0);b.add(new Market.Candle(t+120*f.duration(),4000,4001,3999,4000));chart.data(ZoneEngine.calculate(b,f,t+121*f.duration()),4001.25);chart.draw(canvas);check(selected.equals(chart.getContentDescription().toString()),"Candle timestamp and accessible OHLC survive rolling history refresh");
            touch(chart,MotionEvent.ACTION_DOWN,200,220);touch(chart,MotionEvent.ACTION_MOVE,500,220);touch(chart,MotionEvent.ACTION_UP,500,220);check(chart.getContentDescription().toString().contains("Viewing history"),"Chart pans into history");chart.zoom(true);chart.draw(canvas);check(chart.getContentDescription().toString().contains("Viewing history"),"Zoom preserves historical pan");
            chart.latest();check(chart.getContentDescription().toString().contains("Latest candles"),"Latest control returns to live edge");bitmap.recycle();
        }catch(Throwable e){uiFailure.set(e);}});
        if(uiFailure.get()!=null)throw new AssertionError(uiFailure.get());checks+=4;
        return checks;
    }
    private static void trusted(MarketRepository repo)throws Exception{java.lang.reflect.Field field=MarketRepository.class.getDeclaredField("marketClock");field.setAccessible(true);((MarketClock)field.get(repo)).sync(System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime());}
    private static void touch(CandleChart chart,int action,float x,float y){MotionEvent event=MotionEvent.obtain(0,0,action,x,y,0);chart.onTouchEvent(event);event.recycle();}
}
