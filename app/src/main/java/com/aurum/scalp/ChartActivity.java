package com.aurum.scalp;

import android.app.Activity;import android.app.AlertDialog;import android.content.*;import android.graphics.Typeface;import android.graphics.drawable.GradientDrawable;import android.os.*;import android.view.*;import android.widget.*;

/** Dedicated chart surface sharing the same repository/service as the rest of Aurum. */
public final class ChartActivity extends Activity {
    private MonitorRuntime runtime;private AdvancedChartView chart;private Market.Frame frame;private LinearLayout root,chrome;private FrameLayout stage;private Button exit;private TextView state,fullLabel;private boolean visible,full;
    private final MonitorRuntime.Listener listener=this::update;private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable clock=new Runnable(){public void run(){if(visible){update();handler.postDelayed(this,5000);}}};
    private android.window.OnBackInvokedCallback back;
    private AlertDialog layerMenu;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);runtime=MonitorRuntime.get(this);
        try{frame=Market.Frame.valueOf(saved!=null?saved.getString("frame","H1"):getIntent().hasExtra("default-frame")?getIntent().getStringExtra("default-frame"):prefs().getString("frame","H1"));}catch(Exception e){frame=Market.Frame.H1;}
        root=column();root.setBackgroundColor(MainActivity.BG);setContentView(root);root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(full?WindowInsets.Type.displayCutout():WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
            else v.setPadding(insets.getSystemWindowInsetLeft(),full?0:insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),full?0:insets.getSystemWindowInsetBottom());return insets;
        });
        chrome=column();chrome.setPadding(dp(10),dp(5),dp(10),dp(4));root.addView(chrome);
        LinearLayout title=row();Button close=button("Back",false);close.setContentDescription("Back from chart");close.setOnClickListener(v->finish());title.addView(close);
        LinearLayout heading=column();TextView name=text("AURUM / CHART",13,MainActivity.GOLD);name.setTypeface(null,Typeface.BOLD);heading.addView(name);state=text("Connecting",10,MainActivity.MUTED);heading.addView(state);LinearLayout.LayoutParams hlp=new LinearLayout.LayoutParams(0,-2,1);hlp.setMargins(dp(10),0,dp(8),0);title.addView(heading,hlp);
        Button expand=button("Expand",false);expand.setContentDescription("Enter fullscreen chart");expand.setOnClickListener(v->fullscreen(true));title.addView(expand);chrome.addView(title);
        HorizontalScrollView frames=new HorizontalScrollView(this);frames.setHorizontalScrollBarEnabled(false);LinearLayout chips=row();
        for(Market.Frame f:Market.Frame.values()){Button b=button(f.label,f==frame);b.setContentDescription("Chart timeframe "+f.label);b.setTag(f);b.setOnClickListener(v->{frame=f;runtime.viewFrames(listener,chartFrames());prefs().edit().putString("frame",frame.name()).apply();for(int i=0;i<chips.getChildCount();i++){Button item=(Button)chips.getChildAt(i);style(item,item.getTag()==frame);}update();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(62),-2);lp.setMargins(0,dp(5),dp(6),0);chips.addView(b,lp);}frames.addView(chips);chrome.addView(frames);
        LinearLayout controls=row();
        Button layers=button("Layers",false);layers.setContentDescription("Chart layers");layers.setOnClickListener(v->showLayers());
        Button focus=button("Focus price",true);focus.setContentDescription("Focus price on recent candles");focus.setOnClickListener(v->chart.focusPrice());
        Button latest=button("Latest",false);latest.setContentDescription("Return chart to latest and reset scale");latest.setOnClickListener(v->chart.latest());
        for(Button control:new Button[]{layers,focus,latest}){
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(0,dp(5),dp(5),0);controls.addView(control,lp);
        }
        chrome.addView(controls);
        stage=new FrameLayout(this);chart=new AdvancedChartView(this);stage.addView(chart,new FrameLayout.LayoutParams(-1,-1));exit=button("Exit fullscreen",true);exit.setContentDescription("Exit fullscreen chart");exit.setVisibility(View.GONE);exit.setOnClickListener(v->fullscreen(false));FrameLayout.LayoutParams elp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END);elp.setMargins(dp(8),dp(2),dp(8),0);stage.addView(exit,elp);fullLabel=text("AURUM / CHART",12,MainActivity.GOLD);fullLabel.setVisibility(View.GONE);FrameLayout.LayoutParams flp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);flp.setMargins(dp(12),dp(14),0,0);stage.addView(fullLabel,flp);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        if(Build.VERSION.SDK_INT>=33){back=()->{if(full)fullscreen(false);else finish();};getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,back);}
        fullscreen(saved!=null&&saved.getBoolean("full",false));update();
    }
    private static final String[] LAYER_KEYS={"zones","context","ema","live","near"};
    private static final String[] LAYER_NAMES={"Zones","1H / 4H context","EMA 50/200","Live candle","Near zones"};
    private void showLayers(){
        boolean[] selected=new boolean[LAYER_KEYS.length];
        for(int i=0;i<selected.length;i++)selected[i]=prefs().getBoolean(LAYER_KEYS[i],i!=2);
        layerMenu=new AlertDialog.Builder(this).setTitle("Chart layers")
            .setMultiChoiceItems(LAYER_NAMES,selected,(dialog,which,on)->{prefs().edit().putBoolean(LAYER_KEYS[which],on).apply();update();})
            .setPositiveButton("Done",null).setNeutralButton("Refresh data",(dialog,which)->runtime.repository.refresh(frame)).show();
    }
    private SharedPreferences prefs(){return getSharedPreferences("advanced-chart",MODE_PRIVATE);}
    private void update(){if(chart==null)return;MarketRepository r=runtime.repository;fullLabel.setText(getString(R.string.chart_heading,frame.label));state.setText(getString(R.string.chart_state,frame.label,Market.priceState(r.quote,r.quoteOnline,r.marketNow(),r.analyses.values()).replace("● ","")));chart.data(r,frame,prefs().getBoolean("zones",true),prefs().getBoolean("context",true),prefs().getBoolean("ema",false),prefs().getBoolean("live",true),prefs().getBoolean("near",true));}
    @SuppressWarnings("deprecation")
    private void fullscreen(boolean value){full=value;chrome.setVisibility(value?View.GONE:View.VISIBLE);exit.setVisibility(value?View.VISIBLE:View.GONE);fullLabel.setVisibility(value?View.VISIBLE:View.GONE);FrameLayout.LayoutParams clp=(FrameLayout.LayoutParams)chart.getLayoutParams();clp.topMargin=value?dp(48*Math.max(1,getResources().getConfiguration().fontScale)):0;chart.setLayoutParams(clp);
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(value)controller.hide(WindowInsets.Type.systemBars());else controller.show(WindowInsets.Type.systemBars());}}
        else getWindow().getDecorView().setSystemUiVisibility(value?View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY:View.SYSTEM_UI_FLAG_VISIBLE);
        root.requestApplyInsets();
    }
    @Override protected void onStart(){super.onStart();visible=true;runtime.attach(listener,chartFrames());chart.active(true);update();handler.post(clock);}
    @Override protected void onStop(){visible=false;handler.removeCallbacks(clock);chart.active(false);runtime.detach(listener);super.onStop();}
    private java.util.Set<Market.Frame> chartFrames(){
        return java.util.EnumSet.of(frame,Market.Frame.H1,Market.Frame.H4);
    }
    @Override protected void onResume(){super.onResume();runtime.refreshPermissions();}
    @Override protected void onDestroy(){
        if(layerMenu!=null)layerMenu.dismiss();
        stage.removeView(chart);
        chart.stopLoading();
        chart.destroy();
        super.onDestroy();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);root.requestApplyInsets();update();}
    @Override protected void onSaveInstanceState(Bundle b){b.putString("frame",frame.name());b.putBoolean("full",full);super.onSaveInstanceState(b);}
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){if(full)fullscreen(false);else super.onBackPressed();}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private Button button(String title,boolean selected){Button b=new Button(this);b.setText(title);b.setTextSize(12);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(44*Math.max(1,getResources().getConfiguration().fontScale)));b.setPadding(dp(10),dp(5),dp(10),dp(5));style(b,selected);return b;}
    private void style(Button b,boolean selected){b.setTextColor(selected?MainActivity.BG:MainActivity.MUTED);GradientDrawable d=new GradientDrawable();d.setColor(selected?MainActivity.GOLD:MainActivity.SURFACE);d.setCornerRadius(dp(9));d.setStroke(dp(1),MainActivity.BORDER);b.setBackground(d);b.setSelected(selected);}
}
