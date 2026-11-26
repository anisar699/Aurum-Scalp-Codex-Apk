package com.aurum.scalp;
import android.annotation.SuppressLint;import android.content.*;import android.graphics.Color;import android.net.Uri;import android.os.*;import android.webkit.*;import android.widget.*;
import java.io.*;import java.util.*;

/** Local renderer only. The page has no feed, proxy route or JavaScript interface. */
final class AdvancedChartView extends WebView {
    private static final String HOST="appassets.androidplatform.net";
    private boolean ready,active;private MarketRepository repo;private Market.Frame frame;private boolean zones,context,ema,live,near,sessions,sweeps;private Object sent;private Market.Frame sentFrame;private boolean queued;
    private final SessionLevels.Cache sessionCache=new SessionLevels.Cache();
    private final Handler main=new Handler(Looper.getMainLooper());private final Runnable push=()->{queued=false;send();};
    @SuppressLint("SetJavaScriptEnabled")
    AdvancedChartView(Context c){
        super(c);setBackgroundColor(Color.rgb(11,17,24));setContentDescription("Interactive MID candle chart. Pinch to zoom; drag to explore; long press for candle details.");
        getSettings().setJavaScriptEnabled(true);getSettings().setTextZoom(100);getSettings().setAllowFileAccess(false);getSettings().setAllowContentAccess(false);getSettings().setDomStorageEnabled(false);getSettings().setSupportZoom(false);getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
                Uri u=request.getUrl();String path=u.getPath();
                if(!"https".equals(u.getScheme())||!HOST.equals(u.getHost())||path==null||path.contains("..")||!Arrays.asList("/index.html","/chart.js","/vendor/lightweight-charts.standalone.production.js").contains(path))return response(403,"text/plain","Blocked".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try(InputStream in=getContext().getAssets().open("chart"+path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return response(200,path.endsWith(".html")?"text/html":"text/javascript",out.toByteArray());}catch(IOException e){return response(404,"text/plain",new byte[0]);}
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                Uri u=r.getUrl();if("https".equals(u.getScheme())&&Arrays.asList("www.tradingview.com","tradingview.com","biquote.io").contains(u.getHost()))try{getContext().startActivity(new Intent(Intent.ACTION_VIEW,u));}catch(ActivityNotFoundException ignored){}return true;
            }
            @Override public void onPageFinished(WebView v,String url){if(url.equals("https://"+HOST+"/index.html")){probeRenderer();}}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){
                ready=false;active=false;main.removeCallbacks(push);
                Toast.makeText(getContext(),"Chart renderer stopped. Reopen Chart or use the Market price map.",Toast.LENGTH_LONG).show();
                if(getContext() instanceof android.app.Activity)((android.app.Activity)getContext()).finish();
                return true;
            }
        });loadUrl("https://"+HOST+"/index.html");
    }
    private void probeRenderer(){evaluateJavascript("typeof window.AurumChart==='object' && typeof window.AurumChart.render==='function'",value->{
        ready="true".equals(value);sent=null;if(ready)send();else evaluateJavascript("document.getElementById('legend').textContent='Chart unavailable';document.getElementById('empty').textContent='Update Android System WebView and reopen Chart. The native price map is available on Market.';document.getElementById('empty').hidden=false;document.getElementById('status').textContent='Renderer could not start';",null);
    });}
    private static WebResourceResponse response(int code,String mime,byte[] data){return new WebResourceResponse(mime,"utf-8",code,code==200?"OK":"Blocked",Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream(data));}
    void active(boolean value){active=value;if(value){onResume();update();}else{main.removeCallbacks(push);queued=false;onPause();}}
    void data(MarketRepository r,Market.Frame f,boolean zones,boolean context,boolean ema,boolean live,boolean near,boolean sessions,boolean sweeps){repo=r;frame=f;this.zones=zones;this.context=context;this.ema=ema;this.live=live;this.near=near;this.sessions=sessions;this.sweeps=sweeps;update();}
    private void update(){if(active&&!queued){queued=true;main.postDelayed(push,150);}}
    private void send(){if(!active||!ready||repo==null||frame==null)return;try{
        Object identity=repo.analyses.get(frame);boolean history=sentFrame!=frame||sent!=identity;
        org.json.JSONObject data=ChartPayload.make(repo,frame,history,zones,context,ema,live,near,getResources().getConfiguration().fontScale);
        if(sessions||sweeps&&frame==Market.Frame.M5)ChartPayload.addSessions(data,repo,sessionCache,sessions,sweeps&&frame==Market.Frame.M5);
        evaluateJavascript("window.AurumChart&&window.AurumChart.render("+data+")",null);sent=identity;sentFrame=frame;
    }catch(org.json.JSONException ignored){}}
    void focusPrice(){if(ready)evaluateJavascript("window.AurumChart.focusPrice()",null);}
    void latest(){if(ready)evaluateJavascript("window.AurumChart.latest()",null);}
    @Override public void destroy(){active=false;main.removeCallbacksAndMessages(null);super.destroy();}
}
