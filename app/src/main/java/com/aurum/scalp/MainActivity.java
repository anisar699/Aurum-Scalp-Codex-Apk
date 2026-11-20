package com.aurum.scalp;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class MainActivity extends Activity {
    public static final int BG=0xFF0B1118,SURFACE=0xFF141E29,BORDER=0xFF273545,TEXT=0xFFF1F4F4,MUTED=0xFFA5B5C6,GOLD=0xFFE9C981,GREEN=0xFF7BDCB6,RED=0xFFFF9E9A;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("dd MMM HH:mm 'UTC'",Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter EXACT=DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss 'UTC'",Locale.US).withZone(ZoneOffset.UTC);
    private MarketRepository repo;
    private MonitorRuntime runtime;
    private final MonitorRuntime.Listener repositoryListener=this::changed;
    private Button monitorButton;
    private boolean startAfterPermission;
    private final Map<Market.Frame,ZoneEngine.Analysis> regimeSource=new EnumMap<>(Market.Frame.class);
    private final Map<Market.Frame,MarketRegime.Readout> regimes=new EnumMap<>(Market.Frame.class);
    private LinearLayout root,body,nav,nearPanel,zonePanel;
    private ScrollView scroll;
    private TextView bidView,askView,liveView,tickView,banner,insightView,sourceView;
    private Button refresh;
    private CandleChart chart;
    private Market.Frame frame=Market.Frame.M1;
    private int page;
    private android.window.OnBackInvokedCallback methodBack;
    private boolean resumed;
    private Map<Market.Frame,ZoneEngine.Analysis> shown=Collections.emptyMap();
    private String zoneSignature;
    private ZoneSelection.Distance zoneFilter=ZoneSelection.Distance.ALL;
    private final List<Runnable> pageBindings=new ArrayList<>();
    private final List<Runnable> quoteBindings=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable clock=new Runnable(){@Override public void run(){if(resumed){liveUpdate();handler.postDelayed(this,5000);}}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{frame=Market.Frame.valueOf(state!=null?state.getString("frame","M1"):getPreferences(MODE_PRIVATE).getString("frame","M1"));}catch(Exception ignored){frame=Market.Frame.M1;}
        page=state==null?0:state.getInt("page",0);
        try{zoneFilter=new ZoneSelection.Distance(ZoneSelection.Unit.valueOf(getPreferences(MODE_PRIVATE).getString("distance-unit","ALL")), getPreferences(MODE_PRIVATE).getFloat("distance-limit",0));}catch(IllegalArgumentException ignored){}
        runtime=MonitorRuntime.get(this);repo=runtime.repository;readIntent(getIntent());
        root=column();root.setBackgroundColor(BG);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->applyInsets(v,insets));
        LinearLayout header=row();header.setPadding(dp(18),dp(10),dp(18),dp(10));LinearLayout brand=column();brand.addView(label("A U R U M",19,GOLD,true));gap(brand,3);brand.addView(label("SCALP  /  PRICE STRUCTURE  ·  "+BuildConfig.VERSION_NAME,9,MUTED,false));header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        refresh=button("Refresh",false);refresh.setContentDescription("Refresh market data");refresh.setOnClickListener(v->repo.refresh(page==3||page==4?null:frame));header.addView(refresh,new LinearLayout.LayoutParams(-2,-2));root.addView(header);root.addView(line(),new LinearLayout.LayoutParams(-1,dp(1)));
        scroll=new ScrollView(this);scroll.setFillViewport(true);body=column();body.setPadding(dp(18),dp(16),dp(18),dp(22));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=row();nav.setPadding(dp(10),dp(7),dp(10),dp(7));root.addView(line(),new LinearLayout.LayoutParams(-1,dp(1)));root.addView(nav);
        render();
    }
    @Override protected void onStart(){super.onStart();resumed=true;runtime.attach(repositoryListener,visibleFrames());repo=runtime.repository;runtime.refreshState();handler.post(clock);}
    @Override protected void onResume(){super.onResume();runtime.refreshPermissions();runtime.refreshState();}
    private Set<Market.Frame> visibleFrames(){
        if(page==2||page==4)return EnumSet.allOf(Market.Frame.class);
        if(page==6)return EnumSet.of(Market.Frame.M5,Market.Frame.M15,Market.Frame.H1,Market.Frame.H4);
        return EnumSet.of(frame,Market.Frame.H1,Market.Frame.H4);
    }
    @Override protected void onStop(){resumed=false;handler.removeCallbacks(clock);runtime.detach(repositoryListener);super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("frame",frame.name());state.putInt("page",page);super.onSaveInstanceState(state);}
    @SuppressWarnings("deprecation")
    private WindowInsets applyInsets(View view,WindowInsets insets){
        int extra=Math.max(0,(getResources().getDisplayMetrics().widthPixels-dp(660))/2);
        if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(bars.left+extra,bars.top,bars.right+extra,bars.bottom);}
        else view.setPadding(insets.getSystemWindowInsetLeft()+extra,insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight()+extra,insets.getSystemWindowInsetBottom());
        return insets;
    }
    private void backToAnalysis(){page=2;scroll.scrollTo(0,0);render();}
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed(){if(page==6)showPage(0);else if(page==3||page==4)backToAnalysis();else super.onBackPressed();}
    private void updateBackHandler(){
        if(Build.VERSION.SDK_INT>=33){
            if((page==3||page==4||page==6)&&methodBack==null){methodBack=()->{if(page==6)showPage(0);else backToAnalysis();};getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,methodBack);}
            else if(page!=3&&page!=4&&page!=6&&methodBack!=null){getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(methodBack);methodBack=null;}
        }
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);root.requestApplyInsets();render();}
    private ZoneEngine.Analysis analysis(){return repo.analyses.get(frame);}
    private double price(){return Market.referencePrice(repo.quote,repo.analyses.values());}
    private void changed(){
        repo=runtime.repository;
        if(shown!=repo.analyses){
            shown=repo.analyses;ZoneEngine.Analysis a=analysis();
            if(a!=null&&a.candles.size()>=40&&((page==0&&nearPanel!=null&&zonePanel!=null&&chart!=null)||(page==1&&zonePanel!=null))){
                if(page==0)chart.data(a,price());set(sourceView,sourceText(a));liveUpdate();
            }else if(page==2||page==3||page==4||page==6)liveUpdate();else render();
        }else liveUpdate();
    }
    private void render(){
        if(body==null)return;runtime.viewFrames(repositoryListener,visibleFrames());int y=scroll.getScrollY();body.removeAllViews();nearPanel=zonePanel=null;bidView=askView=liveView=tickView=banner=insightView=sourceView=null;quoteBindings.clear();pageBindings.clear();zoneSignature=null;
        renderNav();updateBackHandler();
        if(page==3){method();}else if(page==4){health();}else if(page==6){quoteCard();space(12);scalpView();}else{
            quoteCard();space(12);frameButtons();space(14);
            if(page==0)market();else if(page==1)levels();else analyst();
        }
        liveUpdate();scroll.post(()->scroll.scrollTo(0,y));
    }
    private void renderNav(){
        nav.removeAllViews();boolean stacked=getResources().getConfiguration().fontScale>=1.3f&&getResources().getDisplayMetrics().widthPixels<dp(600);
        nav.setOrientation(stacked?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);LinearLayout target=nav;
        String[] titles={"Market","Chart","All zones","Analysis","Health"};
        int[] pages={0,5,1,2,4};
        for(int i=0;i<titles.length;i++){
            if(stacked&&i%3==0){target=row();nav.addView(target,new LinearLayout.LayoutParams(-1,-2));}
            final int index=pages[i];boolean selected=page==index||(page==3&&index==2)||(page==6&&index==0);Button b=button(titles[i],selected);if(!stacked)b.setTextSize(11);b.setContentDescription(titles[i]+(selected?", selected":""));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,controlHeight(46),1);lp.setMargins(dp(3),stacked?dp(2):0,dp(3),stacked?dp(2):0);target.addView(b,lp);
            b.setOnClickListener(v->{if(index==5){startActivity(new Intent(this,ChartActivity.class).putExtra("default-frame",frame.name()));return;}page=index;scroll.scrollTo(0,0);render();});
        }
    }
    private void quoteCard(){
        LinearLayout card=card(16);LinearLayout top=row();top.addView(label("XAU / USD  ·  MID",13,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));liveView=label("CONNECTING",10,GOLD,true);top.addView(liveView);card.addView(top);gap(card,7);
        LinearLayout prices=column();bidView=label("—",33,TEXT,false);bidView.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));prices.addView(bidView,new LinearLayout.LayoutParams(-1,-2));askView=label("Bid / ask",11,MUTED,false);askView.setGravity(Gravity.END);prices.addView(askView,new LinearLayout.LayoutParams(-1,-2));card.addView(prices);gap(card,7);
        tickView=label(repo.sourceName()+" · USD per troy ounce",10,MUTED,false);card.addView(tickView);body.addView(card,new LinearLayout.LayoutParams(-1,-2));
        card.addView(bound(()->runtime.background()?"BACKGROUND MONITORING ON · "+runtime.alertState():"Monitoring while app is open · Start background mode in Health",10,GOLD));
        banner=label("",11,GOLD,false);banner.setPadding(dp(1),dp(8),dp(1),0);body.addView(banner);
    }
    private void frameButtons(){
        body.addView(label(frame.scalp()?"SCALP  ·  1m execution + 5m / 15m context":"STRUCTURE  ·  Higher-timeframe context",10,GOLD,true));space(8);LinearLayout chips=row();
        for(Market.Frame f:Market.Frame.values()){Button b=button(f.label,frame==f);b.setTextSize(13);b.setContentDescription(f.label+" timeframe"+(frame==f?", selected":""));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,controlHeight(44),1);lp.setMargins(0,0,dp(5),0);chips.addView(b,lp);b.setOnClickListener(v->{frame=f;getPreferences(MODE_PRIVATE).edit().putString("frame",f.name()).apply();scroll.scrollTo(0,0);if(chart!=null)chart.latest();render();});}body.addView(chips);
    }
    private void liveUpdate(){
        if(repo==null)return;
        if(refresh!=null){
            long retry=repo.backoffRemaining();
            set(refresh,retry>0?"Retry in "+age(retry):repo.loading()?"Refreshing…":"Refresh");
            refresh.setEnabled(retry<=0);
        }
        Market.Quote q=repo.quote;long now=repo.marketNow();double price=price();
        if(bidView!=null){
            set(bidView,price>0?money(price):"—");
            String state=Market.priceState(q,repo.quoteOnline,now,repo.analyses.values());
            boolean candleReference=state.contains("CANDLE REFERENCE");
            set(liveView,state);liveView.setTextColor(state.contains("LIVE")?GREEN:GOLD);
            set(askView,q==null?(price>0?"LAST CLOSED PRICE":"USD / TROY OZ"):"Bid "+money(q.bid)+" · Ask "+money(q.ask)+"\nSpread $"+money(q.spread()));
            set(tickView,q==null?repo.sourceName()+" · waiting for a quote":TIME.format(Instant.ofEpochMilli(q.time))+" · "+(now>0?age(Math.max(0,now-q.time))+" ago":"age unverified")+" · MID quote");
            String error=q!=null&&q.marketClosed?"Market closed · last provider quote; live alerts paused.":"";
            if(!repo.quoteError.isEmpty())error+=(error.isEmpty()?"":"\n")+repo.quoteError+". Saved data remains available.";
            else if(q!=null&&!q.marketClosed&&now>0&&now-q.time>90_000)error="No fresh ticks: feed delayed or market closed. Check the timestamp.";
            ZoneEngine.Analysis a=analysis();String history=repo.errors.get(frame);
            if(history!=null)error+=(error.isEmpty()?"":"\n")+frame.label+" history: "+history;
            else if(a!=null&&!a.historyFresh(now))error+=(error.isEmpty()?"":"\n")+frame.label+" · "+candleSummary(frame);
            if((q==null||candleReference)&&price>0)error+=(error.isEmpty()?"":"\n")+"Distances and chart use a newer closed-candle reference; bid/ask metadata is shown separately.";
            if(a!=null&&a.candles.size()<frame.lookback+frame.wing*2)error+=(error.isEmpty()?"":"\n")+"Limited "+frame.label+" history: "+a.candles.size()+" closed bars available; older zones may be absent.";
            set(banner,error);banner.setTextColor(repo.quoteError.isEmpty()&&history==null?GOLD:RED);banner.setVisibility(error.isEmpty()?View.GONE:View.VISIBLE);
        }
        ZoneEngine.Analysis a=analysis();
        if(a!=null&&(nearPanel!=null||zonePanel!=null)){
            String signature=signature(a,price);if(!signature.equals(zoneSignature)){zoneSignature=signature;rebuildZones();}
            for(Runnable binding:new ArrayList<>(quoteBindings))binding.run();
        }
        if(chart!=null&&page==0)chart.price(price);
        if(insightView!=null&&a!=null)set(insightView,insight(a,price));
        for(Runnable binding:new ArrayList<>(pageBindings))binding.run();
    }
    private String signature(ZoneEngine.Analysis a,double p){
        StringBuilder s=new StringBuilder();for(boolean side:new boolean[]{true,false}){
            List<ZoneEngine.Zone> list=new ArrayList<>(near(a,p,side,page==1?6:3));
            list.addAll(ZoneSelection.watches(a,p,side,2,zoneFilter));
            wider(a,p,side).ifPresent(list::add);
            for(ZoneEngine.Zone z:list)s.append(z.id).append('|').append(side).append('|').append(z.low).append('|').append(z.high).append('|').append(z.cap).append('|').append(z.tests).append('|').append(z.defendedVisits).append('|').append(z.failedVisits).append('|').append(z.breakPending).append('|').append(z.barsSinceReaction).append('|').append(z.rejectionAtr).append('|').append(z.flipped).append('|').append(z.retestWatch).append('|').append(z.reference).append('|').append(z.lastTest).append('|').append(z.confirmedAt).append('|').append(ZoneEngine.context(z,repo.analyses.values())).append(';');
        }return s.toString();
    }
    private void market(){
        presets(false);space(12);
        ZoneEngine.Analysis a=analysis();if(a==null||a.candles.size()<40){loading();return;}
        body.addView(bound(()->frame.label+" · "+candleSummary(frame),11,GOLD));space(8);
        nearPanel=row();body.addView(nearPanel);space(14);
        regimeCard();space(12);
        insightView=label(insight(a,price()),12,MUTED,false);body.addView(insightView);space(18);
        Button advanced=button("Open advanced chart",true);advanced.setOnClickListener(v->startActivity(new Intent(this,ChartActivity.class).putExtra("default-frame",frame.name())));body.addView(advanced,new LinearLayout.LayoutParams(-1,-2));space(10);
        LinearLayout map=card(11);LinearLayout controls=row();controls.addView(label(frame.label+" PRICE MAP",11,MUTED,true),new LinearLayout.LayoutParams(0,-2,1));
        Button minus=small("−","Zoom out chart",40),plus=small("+","Zoom in chart",40),latest=small("Latest","Return to latest candles",59);controls.addView(minus);controls.addView(plus);controls.addView(latest);map.addView(controls);
        if(chart==null)chart=new CandleChart(this);if(chart.getParent()!=null)((android.view.ViewGroup)chart.getParent()).removeView(chart);chart.data(a,price());map.addView(chart,new LinearLayout.LayoutParams(-1,dp(235)));minus.setOnClickListener(v->chart.zoom(false));plus.setOnClickListener(v->chart.zoom(true));latest.setOnClickListener(v->chart.latest());
        map.addView(label("Tap: OHLC  ·  Drag: history  ·  Gold: reference  ·  UTC",9,MUTED,false));body.addView(map);space(20);
        heading("Nearby zones & references",frame.scalp()?"Confirmed swings and clearly labeled retest watches":"Context areas; switch to 1m / 5m for execution");
        zonePanel=column();body.addView(zonePanel);space(16);sourceNote(a);
    }
    private void levels(){
        ZoneEngine.Analysis a=analysis();heading(frame.label+" support & resistance","Zone width, independent reactions and distance");
        body.addView(bound(()->candleSummary(frame),11,GOLD));space(8);
        Button filter=button("Distance filter · "+zoneFilter.label(),false);filter.setOnClickListener(v->chooseFilter());body.addView(filter);space(10);
        if(a==null||a.candles.size()<40){loading();return;}
        zonePanel=column();body.addView(zonePanel);space(16);Button share=button("Share current zones",true);share.setOnClickListener(v->share());body.addView(share,new LinearLayout.LayoutParams(-1,controlHeight(46)));space(18);sourceNote(a);
    }
    private List<ZoneEngine.Zone> near(ZoneEngine.Analysis a,double price,boolean support,int count){
        return ZoneSelection.confirmed(a,price,support,count,zoneFilter);
    }
    private Optional<ZoneEngine.Zone> wider(ZoneEngine.Analysis a,double price,boolean support){
        if(zoneFilter.unit()==ZoneSelection.Unit.ALL)return Optional.empty();
        return ZoneSelection.confirmed(a,price,support,Integer.MAX_VALUE,ZoneSelection.Distance.ALL).stream()
            .filter(z->!zoneFilter.includes(z,price,a.atr)).findFirst();
    }
    private void rebuildZones(){
        ZoneEngine.Analysis a=analysis();if(a==null)return;
        double price=price();quoteBindings.clear();
        if(nearPanel!=null){
            nearPanel.removeAllViews();
            for(boolean support:new boolean[]{true,false}){
                List<ZoneEngine.Zone> list=near(a,price,support,1);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
                if(support)lp.setMargins(0,0,dp(8),0);
                nearPanel.addView(nearestCard(list.isEmpty()?null:list.get(0),support),lp);
            }
        }
        if(zonePanel==null)return;
        zonePanel.removeAllViews();
        for(boolean support:new boolean[]{false,true}){
            zonePanel.addView(label(support?"CONFIRMED SUPPORT":"CONFIRMED RESISTANCE",10,support?GREEN:RED,true));gap(zonePanel,9);
            List<ZoneEngine.Zone> zones=near(a,price,support,page==1?6:3);
            if(zones.isEmpty()){
                zonePanel.addView(label("No confirmed "+(support?"support":"resistance")+" within the selected distance.",12,MUTED,false));gap(zonePanel,8);
            }
            for(ZoneEngine.Zone z:zones){zonePanel.addView(zoneRow(z));gap(zonePanel,8);}
            gap(zonePanel,8);
        }
        List<ZoneEngine.Zone> watches=new ArrayList<>();
        for(boolean support:new boolean[]{true,false})watches.addAll(ZoneSelection.watches(a,price,support,2,zoneFilter));
        if(!watches.isEmpty()){
            zonePanel.addView(label("WATCH AREAS · UNCONFIRMED",10,GOLD,true));gap(zonePanel,9);
            for(ZoneEngine.Zone z:watches){zonePanel.addView(zoneRow(z));gap(zonePanel,8);}
        }
        if(zoneFilter.unit()!=ZoneSelection.Unit.ALL){
            List<ZoneEngine.Zone> wider=new ArrayList<>();
            for(boolean support:new boolean[]{true,false})wider(a,price,support).ifPresent(wider::add);
            if(!wider.isEmpty()){
                zonePanel.addView(label("WIDER STRUCTURE · OUTSIDE YOUR FILTER",10,MUTED,true));gap(zonePanel,9);
                for(ZoneEngine.Zone z:wider){zonePanel.addView(zoneRow(z));gap(zonePanel,8);}
            }
        }
    }
    private void chooseFilter(){
        LinearLayout input=column();input.setPadding(dp(22),dp(10),dp(22),0);
        RadioGroup units=new RadioGroup(this);
        int selectedId=View.NO_ID;
        for(ZoneSelection.Unit unit:ZoneSelection.Unit.values()){
            RadioButton option=new RadioButton(this);option.setId(View.generateViewId());option.setTag(unit);
            if(unit==zoneFilter.unit())selectedId=option.getId();
            option.setText(unit==ZoneSelection.Unit.ALL?"All distances":unit==ZoneSelection.Unit.USD?"USD from edge":"ATR from edge");
            units.addView(option);
        }
        units.check(selectedId);input.addView(units);
        EditText limit=new EditText(this);limit.setSingleLine();limit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        limit.setHint("Distance limit");if(zoneFilter.limit()>0)limit.setText(ZoneSelection.money(zoneFilter.limit()));
        limit.setEnabled(zoneFilter.unit()!=ZoneSelection.Unit.ALL);units.setOnCheckedChangeListener((group,id)->limit.setEnabled(group.findViewById(id)!=null&&group.findViewById(id).getTag()!=ZoneSelection.Unit.ALL));input.addView(limit);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Zone distance filter").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Apply",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view->{
            View option=units.findViewById(units.getCheckedRadioButtonId());if(option==null)return;
            ZoneSelection.Unit unit=(ZoneSelection.Unit)option.getTag();double amount=0;
            if(unit!=ZoneSelection.Unit.ALL){
                try{amount=Double.parseDouble(limit.getText().toString().trim().replace(',','.'));}catch(NumberFormatException ignoredNumber){}
                if(!Market.finite(amount)||amount<=0||(unit==ZoneSelection.Unit.ATR?amount>100:amount>10000)){limit.setError("Enter a positive distance within range");return;}
            }
            zoneFilter=new ZoneSelection.Distance(unit,amount);
            getPreferences(MODE_PRIVATE).edit().putString("distance-unit",unit.name()).putFloat("distance-limit",(float)amount).apply();
            dialog.dismiss();render();
        }));dialog.show();
    }
    private String candleSummary(Market.Frame f){
        return DataFreshness.summary(repo.analyses.get(f),repo.marketNow(),repo.liveSynced.getOrDefault(f,0L),repo.quote);
    }
    private void showPage(int next){page=next;scroll.scrollTo(0,0);render();}
    private void presets(boolean scalp){
        LinearLayout bar=row();Button quick=button("Scalp View",scalp), structure=button("Structure",!scalp);
        quick.setOnClickListener(v->showPage(6));
        structure.setOnClickListener(v->{if(frame.scalp())frame=Market.Frame.H1;showPage(0);});
        bar.addView(quick,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams second=new LinearLayout.LayoutParams(0,-2,1);second.setMargins(dp(8),0,0,0);bar.addView(structure,second);body.addView(bar);
    }
    private void scalpView(){
        presets(true);space(12);
        heading("Scalp View","5m and 15m execution context · 1H and 4H structure");
        body.addView(bound(()->{
            Market.Quote q=repo.quote;MarketRegime.Readout r=regime(Market.Frame.M5);
            return (q==null?"Spread unavailable":"Spread $"+money(q.spread())+(SmartAlerts.usableQuote(q,repo.quoteOnline,repo.marketNow())?"":" · saved quote"))
                +(r.available?" · 5m volatility "+r.volatility+" · ATR $"+money(r.atr):" · waiting for volatility data");
        },12,GOLD));space(9);
        Button filter=button("Distance filter · "+zoneFilter.label(),false);filter.setOnClickListener(v->chooseFilter());body.addView(filter);space(9);
        for(Market.Frame f:new Market.Frame[]{Market.Frame.M5,Market.Frame.M15,Market.Frame.H1,Market.Frame.H4}){
            LinearLayout panel=card(12);
            panel.addView(label(f.label+(f.scalp()?" · INTRADAY":" · STRUCTURE"),12,f.scalp()?GREEN:GOLD,true));
            panel.addView(bound(()->candleSummary(f),10,MUTED));gap(panel,5);
            for(boolean support:new boolean[]{true,false}){
                TextView line=bound(()->{
                    ZoneEngine.Zone z=scalpBand(f,support);
                    String side=support?"Support":"Resistance";
                    if(z==null)return side+" · No zone in range";
                    return side+(z.reference?" watch ":" ")+ZoneSelection.band(z)+" · "
                        +(z.contains(price())?"Reference inside":"$"+money(z.distance(price()))+" from edge")
                        +"\nWidth $"+money(z.width())+" · "+(z.reference?z.evidence():z.defendedVisits+" defended visits");
                },12,support?GREEN:RED);
                line.setMinHeight(dp(44));
                line.setOnClickListener(v->{ZoneEngine.Zone z=scalpBand(f,support);if(z!=null)detail(z);});
                panel.addView(line);
            }
            Button open=button("Open "+f.label+" chart",false);
            open.setOnClickListener(v->startActivity(new Intent(this,ChartActivity.class).putExtra("default-frame",f.name())));panel.addView(open);
            body.addView(panel);space(8);
        }
        Button pins=button("Manage pinned alerts",false);pins.setOnClickListener(v->managePins());body.addView(pins);
    }
    private ZoneEngine.Zone scalpBand(Market.Frame frame,boolean support){
        ZoneEngine.Analysis analysis=repo.analyses.get(frame);
        double price=price();
        List<ZoneEngine.Zone> bands=near(analysis,price,support,1);
        if(bands.isEmpty())bands=ZoneSelection.watches(analysis,price,support,1,zoneFilter);
        return bands.isEmpty()?null:bands.get(0);
    }
    private LinearLayout nearestCard(ZoneEngine.Zone z,boolean support){
        int color=support?GREEN:RED;LinearLayout card=card(12);String title=(support?"SUPPORT":"RESISTANCE")+" · "+frame.label;
        if(z!=null&&z.reference)title=support?"SUPPORT WATCH":"RESISTANCE WATCH";
        card.addView(label(title,9,z!=null&&z.reference?GOLD:color,true));gap(card,7);card.addView(label(z==null?"—":money(z.center),22,TEXT,true));gap(card,4);
        card.addView(label(z==null?"No nearby validated zone":money(z.low)+" – "+money(z.high),10,MUTED,false));gap(card,5);
        TextView distance=label("",11,MUTED,false);card.addView(distance);quoteBindings.add(()->set(distance,z==null?"Wait for a confirmed reaction":z.contains(price())?"Reference inside zone":"$"+money(z.distance(price()))+" from edge"));
        gap(card,5);card.addView(label(z==null?"":z.evidence()+(z.reference?"":" · "+z.tests+" test"+(z.tests==1?"":"s")),10,z!=null&&z.reference?GOLD:color,false));
        if(z!=null){card.setContentDescription((support?"Support":"Resistance")+" zone "+money(z.center)+", "+z.evidence()+". Tap for details.");card.setOnClickListener(v->detail(z));}return card;
    }
    private LinearLayout zoneRow(ZoneEngine.Zone z){
        int color=z.reference?GOLD:z.support?GREEN:RED;LinearLayout card=column();card.setPadding(dp(12),dp(13),dp(12),dp(13));card.setBackground(background(SURFACE,12));View marker=new View(this);marker.setBackground(background(color,2));LinearLayout.LayoutParams ml=new LinearLayout.LayoutParams(-1,dp(3));ml.setMargins(0,0,0,dp(8));card.addView(marker,ml);
        LinearLayout text=column();card.addView(text,new LinearLayout.LayoutParams(-1,-2));text.addView(label(money(z.low)+" – "+money(z.high),14,TEXT,true));gap(text,4);
        String note=z.reference?(z.breakPending?"One buffered close beyond band":z.retestWatch?"Broken zone · retest needed":"Observed range extreme · unconfirmed"):(z.support?"Support":"Resistance")+" · "+z.tests+" reaction"+(z.tests==1?"":"s")+(z.flipped?" · flip retested":"");text.addView(label(note,10,MUTED,false));
        if(!z.reference){gap(text,4);text.addView(label(z.defendedVisits+" defended visits since confirmation",10,MUTED,false));}
        String context=ZoneEngine.context(z,repo.analyses.values());if(!context.isEmpty()){gap(text,4);text.addView(label("Overlap · "+context,10,GOLD,false));}
        TextView width=label("Width $"+money(z.width()),10,MUTED,false);gap(text,4);text.addView(width);
        LinearLayout meta=column();TextView evidence=label(z.evidence(),10,color,true);evidence.setGravity(Gravity.END);meta.addView(evidence);gap(meta,5);TextView gap=label("",10,MUTED,false);gap.setGravity(Gravity.END);meta.addView(gap);quoteBindings.add(()->set(gap,z.contains(price())?"Inside zone":"$"+money(z.distance(price()))+" from edge"));
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.setMargins(dp(6),0,0,0);card.addView(meta,mp);card.setOnClickListener(v->detail(z));return card;
    }
    private String insight(ZoneEngine.Analysis a,double price){
        if(!"Current candles".equals(DataFreshness.state(a,repo.marketNow(),repo.liveSynced.getOrDefault(frame,0L),repo.quote)))return "Historical context · "+candleSummary(frame);
        String result="Swing structure · "+a.structure+". ";List<ZoneEngine.Zone> support=near(a,price,true,1),res=near(a,price,false,1);
        boolean atSupport=!support.isEmpty()&&support.get(0).contains(price);
        boolean atResistance=!res.isEmpty()&&res.get(0).contains(price);
        if(atSupport&&atResistance)result+="Reference price is inside overlapping support and resistance.";
        else if(atSupport)result+="Reference price is testing confirmed support.";
        else if(atResistance)result+="Reference price is testing confirmed resistance.";
        else if(support.isEmpty()||res.isEmpty())result+="No confirmed nearby "+(support.isEmpty()?"support":"resistance")+"; watch references are marked separately.";
        else result+="Nearest confirmed edges: support $"+money(support.get(0).distance(price))+" away; resistance $"+money(res.get(0).distance(price))+" away.";
        if(repo.quote!=null&&a.atr>0&&repo.quote.spread()>a.atr*.20)result+=" Spread is large relative to this timeframe's ATR.";
        return result;
    }
    private void detail(ZoneEngine.Zone z){
        String context=ZoneEngine.context(z,repo.analyses.values());
        String msg="Band: "+money(z.low)+" – "+money(z.high)+" USD/oz\nWidth: $"+money(z.width())+"\nEvidence: "+z.evidence()+"\n";
        if(z.breakPending)msg+="One buffered close beyond this band. A second confirms the break. Break and retest tracking continue.\n";
        else if(z.reference)msg+=z.retestWatch?"This zone was broken. Two closed candles have moved beyond it, but a confirmed reaction in the new role has not occurred. It is a watch reference, not active confirmed support/resistance.\n":"This is a recent observed high/low of closed candles. A complete confirmed swing is not available here yet.\n";
        else{
            msg+="Independent swing reactions: "+z.tests+"\nDefended / failed re-entries: "+z.defendedVisits+" / "+z.failedVisits
                +"\nMean swing rejection: "+money(z.rejectionAtr)+" ATR"
                +"\nLast reaction: "+TIME.format(Instant.ofEpochMilli(z.lastTest))+" · "+z.barsSinceReaction+" bars ago"
                +"\nRole: "+(z.flipped?"Broken, then retested in opposite role":"Confirmed swing role")+"\n";
            Market.Quote quote=repo.quote;
            if(z.width()>0&&SmartAlerts.usableQuote(quote,repo.quoteOnline,repo.marketNow())){
                msg+="Live spread: $"+money(quote.spread())+" · "+Math.round(quote.spread()/z.width()*100)+"% of band width\n";
            }
        }
        msg+="\nTimeframe overlap: "+(context.isEmpty()?"None":context)+"\nLevels use provider MID candles, not Exness BID candles. Bid and ask are shown separately; execution prices and broker charts can differ.\n\nBands are bounded during clustering. Prices that do not fit start separate zones; old bands are not widened by today's volatility. Evidence labels are not probabilities.";
        final String copy=msg+"\n"+candleSummary(z.frame)+"\nDistance from edge: $"+money(z.distance(price()));
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(z.frame.label+" zone details").setMessage(copy).setPositiveButton("Close",null)
            .setNeutralButton("Copy",(d,w)->{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("XAUUSD zone",copy));Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show();});
        if(!z.reference||z.retestWatch)dialog.setNegativeButton("Pin alert",(d,w)->choosePin(z));
        dialog.show();
    }
    private void choosePin(ZoneEngine.Zone zone){
        ZoneSelection.Trigger[] options=zone.reference?new ZoneSelection.Trigger[]{ZoneSelection.Trigger.RETEST}:ZoneSelection.Trigger.values();
        String[] titles=Arrays.stream(options).map(MainActivity::triggerName).toArray(String[]::new);
        String title=runtime.pins().containsKey(zone.frame)?"Replace "+zone.frame.label+" pinned alert":"Pin "+zone.frame.label+" zone alert";
        new AlertDialog.Builder(this).setTitle(title).setItems(titles,(d,i)->{
            runtime.pin(zone,options[i]);Toast.makeText(this,"Pinned · "+triggerName(options[i]),Toast.LENGTH_SHORT).show();
            if(!MonitorNotifications.permission(this)&&Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},231);
        }).setNegativeButton("Cancel",null).show();
    }
    private static String triggerName(ZoneSelection.Trigger trigger){
        return trigger==ZoneSelection.Trigger.APPROACH?"Approach":trigger==ZoneSelection.Trigger.BREAK?"Break":"Retest";
    }
    private void managePins(){
        List<ZoneSelection.Pin> pinned=new ArrayList<>(runtime.pins().values());
        if(pinned.isEmpty()){
            new AlertDialog.Builder(this).setTitle("Pinned alerts").setMessage("Open a zone’s details and choose Pin alert. One pin per timeframe.").setPositiveButton("Close",null).show();return;
        }
        String[] names=pinned.stream().map(pin->pin.frame().label+" · "+triggerName(pin.trigger())+" · "+money(pin.low())+"–"+money(pin.high())+pinAvailability(pin)).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Pinned alerts · tap to remove").setItems(names,(d,i)->{
            ZoneSelection.Pin pin=pinned.get(i);
            new AlertDialog.Builder(this).setTitle("Remove "+pin.frame().label+" pin?").setNegativeButton("Cancel",null)
                .setPositiveButton("Remove",(dialog,which)->runtime.removePin(pin.frame())).show();
        }).setNegativeButton("Close",null).show();
    }
    private String pinAvailability(ZoneSelection.Pin pin){
        ZoneEngine.Analysis a=repo.analyses.get(pin.frame());
        ZoneEngine.Zone match=ZoneSelection.resolve(pin,a);
        return match==null?" · zone unavailable":match.breakPending?" · break pending":"";
    }
    private void analyst(){
        ZoneEngine.Analysis a=analysis();heading("Market structure readout","Rules and observed reactions · no win-rate claims");
        LinearLayout readout=card(16);insightView=label(a==null?"Waiting for history":insight(a,price()),14,TEXT,false);readout.addView(insightView);gap(readout,12);readout.addView(bound(()->{ZoneEngine.Analysis current=analysis();return current==null?"": "EMA 20: "+money(current.ema20)+" · EMA 50: "+money(current.ema50)+" · ATR: $"+money(current.atr);},11,MUTED));body.addView(readout);space(16);
        List<Market.Frame> order=new ArrayList<>(Arrays.asList(Market.Frame.values()));order.remove(frame);order.add(0,frame);
        for(Market.Frame f:order){
            LinearLayout box=card(14);box.addView(label(f.label+(f.scalp()?"  /  INTRADAY":"  /  STRUCTURE"),12,f.scalp()?GREEN:GOLD,true));gap(box,7);
            box.addView(bound(()->frameSummary(f),12,MUTED));body.addView(box);space(10);
        }
        priorDay();space(12);Button method=button("Method & data health",true);method.setOnClickListener(v->{page=3;scroll.scrollTo(0,0);render();});body.addView(method,new LinearLayout.LayoutParams(-1,controlHeight(46)));
    }
    private String frameSummary(Market.Frame f){
        ZoneEngine.Analysis a=repo.analyses.get(f);String text=f.label+": "+(a==null?"Waiting for history":"Swing structure · "+a.structure+"\n"+regimeText(f)+"\n"+a.zones.size()+" active bands");
        Long t=repo.synced.get(f);if(t!=null)text+="\nSynced "+EXACT.format(Instant.ofEpochMilli(t));String error=repo.errors.get(f);if(error!=null)text+="\n"+error;return text;
    }
    private TextView bound(java.util.function.Supplier<String> value,int size,int color){TextView view=label(value.get(),size,color,false);pageBindings.add(()->set(view,value.get()));return view;}
    private void priorDay(){LinearLayout c=card(14);c.addView(bound(()->{
        Market.PriorSession p=Market.priorSession(repo.candles,repo.marketNow());if(p==null)return "Prior session unavailable until trusted server time and history load.";
        return "PRIOR TRADING DAY · NY 17:00 ROLLOVER\n"+java.time.LocalDate.ofEpochDay(p.day)+"\nHigh "+money(p.high)+" / Low "+money(p.low)+"\n"+p.bars+" observed "+p.frame.label+" bars; coverage may be partial.";
    },12,MUTED));body.addView(c);}
    private void method(){
        Button back=button("← Back to Analysis",false);back.setOnClickListener(v->backToAnalysis());body.addView(back,new LinearLayout.LayoutParams(-2,controlHeight(44)));space(12);
        heading("Method & data health","Aurum Scalp "+BuildConfig.VERSION_NAME+" · XAU/USD MID prices");
        body.addView(label("Market data by biquote. Live XAUUSD uses a SignalR stream with REST snapshot fallback; no login or key is required. Analysis uses MID-price candles. Bid and ask remain visible for comparison with your broker.",12,GOLD,false));space(14);
        methodCard("01  /  Match the trading horizon","Default 1m execution levels use actual minute candles. 5m and 15m describe intraday context; 1H and 4H provide broader structure. A higher-timeframe zone should not be treated as a precise scalp entry.");
        methodCard("02  /  Confirm swings without unfinished bars","All OHLC data is validated, sorted and filtered to completed UTC bars. 1m, 5m, 15m and 4H swings require 2 closed candles on both sides; 1H requires 3. A rejection must move at least 0.25 of its local ATR. Confirmation waits for those later candles to close; elapsed time varies with session closures. Biquote bars must use the configured UTC bucket alignment. Known provider overnight/weekend closures are allowed between adjacent macro trading bars; unexpected data holes remain disallowed.");
        methodCard("03  /  Bounded bands, no widening old history","Each band uses the volatility at its first confirmed pivot. Its width cap is the larger of a small minimum and the smaller of an ATR fraction and price fraction. Limits: 1m 0.55 ATR / 0.025% of price; 5m 0.45 ATR / 0.06%; 15m 0.40 ATR / 0.09%; 1H 0.32 ATR / 0.12%; 4H 0.25 ATR / 0.18%. Minimum widths are $0.20 / $0.40 / $0.60 / $1.00 / $1.50 respectively. A pivot only joins if the whole price cluster still fits. A band is never cropped after merging to make it appear precise.");
        methodCard("04  /  Confirm breaks and retests in time order","Every closed bar is replayed chronologically. Two consecutive closes beyond a band's edge and buffer invalidate the active role. The zone can become active again only after a newly confirmed pivot in the holding role. Old reaction counts reset after a break. Pending areas appear separately as Retest watch, without a strength score.");
        methodCard("05  /  Count independent evidence","A repeated test needs a later confirmed pivot, separation of at least wing + 1 bars, and a move away from the area. An existing band also logs defended visits after price moves away, comes back from the valid side and closes away again. Swing reactions and visits can describe the same move. Overlap labels require at least half of the smaller current band. A band with one buffered adverse close is marked Break pending.");
        methodCard("06  /  Keep gaps honest","Nearby lists are limited to 10 ATR on intraday frames, 18 ATR on 1H and 24 ATR on 4H. Real confirmed bands and broken-zone retest watches are ordered by distance; the latter are always marked unconfirmed. Recent range extremes appear as Unconfirmed only when no nearby area exists and they are actually on the relevant side of price. No psychological price grid or evenly spaced fake supports are generated. Real gaps can remain during strong one-way moves.");
        methodCard("07  /  biquote prices and candle closure","Live XAUUSD ticks arrive through the biquote SignalR stream; REST snapshots retry at 15s when the stream has no fresh quote (60s for a closed market). History follows UTC candle closes using incremental tails, with hourly full corrections. Provider backoff always wins. Candles must have isOpen=false, valid OHLC, UTC interval alignment and a nominal interval end no later than trusted server time. Open-marked Friday bars stay excluded even on Saturday. No session-close time or missing OHLC is invented.");
        methodCard("08  /  Quote state and storage","Market closed uses the provider state. Live means a successfully received tick at most 30s old; Delayed 30–90s; Stale beyond 90s. Saved means no successful quote connection in the current repository session (shared with background monitoring). Atomic private caching preserves real prior data offline. Obsolete requests from a previous app session cannot overwrite the current one. No random prices, fixed target calibration, custom certificates or demo market history are shipped.");
        methodCard("09  /  Smart alerts","Choose frames in Health (1H and 4H by default). Approach: MID enters within max($0.10, 0.15 ATR) of an active band after moving more than 0.60 ATR away. It rearms only after another departure, with a 10-minute zone cooldown and 2-minute frame cooldown. Approaches do not confirm a reaction. Break: two closed candles beyond the seed-ATR buffer. Retest held: newly confirmed pivot in the new role. Retest failed: two closed candles reclaim the pending band. Each frame confirms its own events. Notifications require trusted server time, a live matching-feed quote and freshly fetched candle history; initial/reconnected snapshots prime the state silently. Android notification settings control sound.");
        methodCard("10  /  Trend, range and volatility","Readout uses at least 80 closed candles. Up/down trend: ADX(14) >= 25, EMA20 on the corresponding side of EMA50, and EMA20 movement over 4 bars >= 0.10 ATR in that direction. Range: ADX < 20 and 20-bar efficiency < 0.35; otherwise transition/mixed. Volatility compares ATR(14) to the median of up to 100 preceding ATR values: below 0.75x low, above 1.50x high. The observed last-20-bar range is context, not a new S/R zone or forecast. These rules have no claimed predictive win rate.");
        for(Market.Frame f:Market.Frame.values()){body.addView(bound(()->frameSummary(f),12,MUTED));space(12);}
        body.addView(label("biquote aggregates third-party data, including MT5 Broker 1 ticks and historical feeds that can have different upstream origins. Recent sampled candles matched MID aggregation; all levels and distances here use MID. Support and resistance needs 40 closed candles, trend metrics need 80, and EMA200 needs 200. Limited depth is labelled. Local biquote-only caching accumulates later confirmed bars. The free service has no uptime or delay SLA. Exness prices, spreads and sessions can differ. Historical reactions are not a trading accuracy guarantee.",12,MUTED,false));space(16);
        Button docs=button("biquote documentation & terms",false);docs.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://biquote.io/terms.html"))));body.addView(docs,new LinearLayout.LayoutParams(-1,-2));space(12);
        body.addView(label("Privacy: no account, advertisements, analytics or order execution. Internet, foreground data-sync service and notification permissions only. Requests go to the market-data provider using Android HTTPS validation. Background polling runs only after you tap Start monitoring. Stop it from Health or the ongoing notification. Android power restrictions and data-sync time limits can pause or stop it; force-stopping the app stops monitoring. It does not automatically start at boot.",11,MUTED,false));
    }
    private void methodCard(String title,String text){LinearLayout c=card(15);c.addView(label(title,14,GOLD,true));gap(c,9);c.addView(label(text,12,MUTED,false));body.addView(c);space(11);}
    private void sourceNote(ZoneEngine.Analysis a){
        sourceView=label(sourceText(a),10,MUTED,false);body.addView(sourceView);
        space(10);body.addView(label("Zones mark historical MID-price reactions. A narrow band does not guarantee a bounce. Check the quote age, candle age and spread.",11,MUTED,false));
    }
    private String sourceText(ZoneEngine.Analysis a){
        String s=repo.sourceName()+" · XAU/USD · "+a.candles.size()+" closed "+frame.label+" bars\nATR(14) $"+money(a.atr)+" · "+a.brokenCount+" broken roles removed";
        if(a.candles.size()<frame.lookback+frame.wing*2)s+="\nLIMITED HISTORY · older reactions may be absent";
        if(!a.candles.isEmpty())s+="\nLast candle close "+TIME.format(Instant.ofEpochMilli(Market.closeTime(a.candles.get(a.candles.size()-1),frame.duration())));return s;
    }
    private void loading(){LinearLayout c=card(18);c.addView(label("Loading "+frame.label+" candle history…",18,TEXT,true));gap(c,10);c.addView(label("Real closed candles are needed before marking zones. Cached data stays available if the connection fails. Tap Refresh to retry.",13,MUTED,false));body.addView(c);}
    private void share(){
        ZoneEngine.Analysis a=analysis();if(a==null)return;StringBuilder s=new StringBuilder("Aurum Scalp "+BuildConfig.VERSION_NAME+" · XAU/USD · "+frame.label+"\n");s.append("Reference ").append(money(price()));if(repo.quote!=null)s.append(" · ").append(EXACT.format(Instant.ofEpochMilli(Market.referenceTime(repo.quote,repo.analyses.values()))));s.append('\n');
        for(boolean support:new boolean[]{true,false})for(ZoneEngine.Zone z:a.nearest(price(),support,3,true))s.append(support?"Support ":"Resistance ").append(money(z.low)).append("–").append(money(z.high)).append(" · ").append(z.evidence()).append(" · width $").append(money(z.width())).append('\n');s.append("Historical MID reactions, not guaranteed forecasts. Market data by biquote; check Method & data health.");Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TEXT,s.toString());startActivity(Intent.createChooser(intent,"Share XAU/USD zones"));
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);readIntent(intent);scroll.scrollTo(0,0);render();}
    private void readIntent(Intent intent){
        if(intent==null)return;if(intent.getBooleanExtra("health",false)){page=4;intent.removeExtra("health");}
        String selected=intent.getStringExtra("alert-frame");if(selected!=null)try{frame=Market.Frame.valueOf(selected);page=0;intent.removeExtra("alert-frame");}catch(IllegalArgumentException ignored){}
    }
    private MarketRegime.Readout regime(Market.Frame f){
        ZoneEngine.Analysis a=repo.analyses.get(f);
        if(!regimes.containsKey(f)||regimeSource.get(f)!=a){regimeSource.put(f,a);regimes.put(f,MarketRegime.calculate(a));}
        return regimes.get(f);
    }
    private String regimeText(Market.Frame f){
        MarketRegime.Readout r=regime(f);if(!r.available)return r.trend;
        String state=candleSummary(f);
        return state+"\nEMA/ADX trend · "+r.trend+" · ADX "+String.format(Locale.US,"%.1f",r.adx)+"\nVolatility "+r.volatility+" · ATR $"+money(r.atr)+" ("+String.format(Locale.US,"%.2f",r.atrRatio)+"x median)\n20-bar range "+money(r.rangeLow)+" – "+money(r.rangeHigh)+"\nLast close at "+Math.round(r.position)+"% of that range · efficiency "+String.format(Locale.US,"%.2f",r.efficiency);
    }
    private void regimeCard(){LinearLayout c=card(14);c.addView(label("TREND / RANGE / VOLATILITY",10,GOLD,true));gap(c,8);c.addView(bound(()->regimeText(frame),12,MUTED));body.addView(c);}
    private void health(){
        heading("Data health & monitoring","One feed · visible freshness · user-controlled alerts");
        LinearLayout status=card(15);status.addView(label("MONITORING",12,GOLD,true));gap(status,8);
        status.addView(bound(()->runtime.background()?"Background monitoring is running":"Background monitoring is off",15,TEXT));gap(status,6);
        status.addView(bound(()->MonitoringWatch.reason(runtime)+"\n"+runtime.alertState(),12,MUTED));gap(status,12);
        monitorButton=button("Start background monitoring",true);
        pageBindings.add(()->set(monitorButton,runtime.background()?"Stop background monitoring":"Start background monitoring"));
        monitorButton.setOnClickListener(v->{if(runtime.background()){startService(new Intent(this,MonitoringService.class).setAction(MonitoringService.STOP));}else requestMonitoring();});status.addView(monitorButton,new LinearLayout.LayoutParams(-1,-2));gap(status,8);
        Button battery=button("Android battery settings",false);battery.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)));status.addView(battery);gap(status,8);
        status.addView(label("The shade includes a Stop button. Android may suspend networking or end data-sync services (Android 15+: 6 hours per 24 hours). Aurum reports a detected stop. Restart from Health.",11,MUTED,false));body.addView(status);space(14);

        LinearLayout controls=card(15);controls.addView(label("SMART ZONE ALERTS",12,GOLD,true));gap(controls,8);
        Switch enabled=new Switch(this);enabled.setText(R.string.smart_zone_notifications);enabled.setTextColor(TEXT);enabled.setTextSize(14);enabled.setMinHeight(controlHeight(48));enabled.setChecked(runtime.alertsEnabled());enabled.setOnCheckedChangeListener((v,checked)->{runtime.alertsEnabled(checked);if(checked&&!MonitorNotifications.permission(this))requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},231);});controls.addView(enabled,new LinearLayout.LayoutParams(-1,-2));gap(controls,6);
        controls.addView(label("Alert timeframes (each confirms its own candles)",11,MUTED,false));
        for(Market.Frame f:Market.Frame.values()){
            CheckBox cb=new CheckBox(this);cb.setText(getString(R.string.alert_timeframe,f.label,f.scalp()?"intraday":"structure"));cb.setTextSize(14);cb.setTextColor(TEXT);cb.setMinHeight(controlHeight(48));cb.setChecked(runtime.frames().contains(f));cb.setOnCheckedChangeListener((v,checked)->runtime.frame(f,checked));controls.addView(cb,new LinearLayout.LayoutParams(-1,-2));
        }
        controls.addView(bound(()->{
            List<String> states=new ArrayList<>();
            for(Market.Frame f:Market.Frame.values())if(runtime.frames().contains(f)||runtime.pins().containsKey(f)){
                long synced=repo.liveSynced.getOrDefault(f,0L);
                states.add(f.label+" · "+runtime.frameReadiness(f)+(synced>0?" · synced "+DataFreshness.date(synced)+" UTC":""));
            }
            return states.isEmpty()?"No alert frames selected":String.join("\n",states);
        },11,MUTED));gap(controls,8);
        Button pins=button("Manage pinned alerts",false);pins.setOnClickListener(v->managePins());controls.addView(pins);gap(controls,8);
        controls.addView(bound(()->"Android notifications: "+(runtime.notificationsAllowed()?"allowed":"blocked / permission needed"),12,GOLD));gap(controls,10);
        if(Build.VERSION.SDK_INT>=33){Button grant=button("Grant notification permission",false);grant.setOnClickListener(v->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},231));controls.addView(grant,new LinearLayout.LayoutParams(-1,-2));gap(controls,8);}
        Button permission=button("Open Android notification settings",false);permission.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())));controls.addView(permission,new LinearLayout.LayoutParams(-1,-2));gap(controls,10);
        controls.addView(label("Approach · confirmed break · retest held · retest failed. Fresh MID quote and verified history required. First snapshots prime silently; recent confirmations can resume after a brief outage; approach alerts need departure/re-entry and cooldown. Alerts describe observations, not buy/sell orders.",11,MUTED,false));gap(controls,9);controls.addView(bound(()->"Last notification: "+runtime.lastAlert(),11,MUTED));body.addView(controls);space(14);

        LinearLayout feed=card(15);feed.addView(label("FEED / CLOCK / REQUEST HEALTH",12,GOLD,true));gap(feed,9);
        feed.addView(bound(()->repo.sourceName()+"\nTrusted server clock: "+(repo.timeTrusted()?EXACT.format(Instant.ofEpochMilli(repo.marketNow())):"unavailable; live alerts paused")+"\nQuote state: "+Market.quoteState(repo.quote,repo.quoteOnline,repo.marketNow())+"\n"+quoteHealth()+"\nStream: "+repo.streamStatus+" · "+repo.streamTicks+" valid tick messages · "+repo.streamSubscriptions+" subscriptions\n"+repo.feedHolds()+"\nRequests since repository start: "+repo.attempts()+" · successful payloads: "+repo.successes()+"\nIn-flight requests: "+(repo.loading()?"yes":"none")+"\n"+(repo.quoteError.isEmpty()?"Last tick fetch: no recorded error":repo.quoteError),12,MUTED));gap(feed,10);
        feed.addView(label("Counts are local HTTP attempts, not provider quota credits. Stream is primary; snapshot fallback targets 15s (60s when closed); history follows candle closes for displayed, monitored and pinned frames. Small tails are merged into the cache between hourly history scans. Request duration and provider backoff add delay. A blocked or closed-market feed cannot produce live alerts.",11,MUTED,false));body.addView(feed);space(14);
        for(Market.Frame f:Market.Frame.values()){
            LinearLayout c=card(14);c.addView(label(f.label+" CANDLE HEALTH",12,GOLD,true));gap(c,8);c.addView(bound(()->candleHealth(f),12,MUTED));body.addView(c);space(10);
        }
        Button method=button("Method & data health",false);method.setOnClickListener(v->{page=3;scroll.scrollTo(0,0);render();});body.addView(method,new LinearLayout.LayoutParams(-1,-2));
    }
    private String quoteHealth(){
        if(repo.quote==null)return "No quote received";long now=repo.marketNow();
        return "Tick time: "+EXACT.format(Instant.ofEpochMilli(repo.quote.time))+"\nQuote age: "+(now<=0||repo.quote.time>now+MarketClock.FUTURE_TOLERANCE?"unverified":age(Math.max(0,now-repo.quote.time)))+" · spread $"+money(repo.quote.spread());
    }
    private String candleHealth(Market.Frame f){
        ZoneEngine.Analysis a=repo.analyses.get(f);long sync=repo.synced.getOrDefault(f,0L);String error=repo.errors.get(f);
        String s=a==null?"No valid history":a.candles.size()+" closed candles · "+a.zones.size()+" active zones · "+a.watchZones.size()+" retest watches";
        if(a!=null&&a.candles.size()<f.lookback+f.wing*2)s+="\nLIMITED HISTORY · older reactions may be absent";
        if(a!=null&&!a.candles.isEmpty()){
            long close=Market.closeTime(a.candles.get(a.candles.size()-1),f.duration());long now=repo.marketNow();
            s+="\nLast close: "+EXACT.format(Instant.ofEpochMilli(close))+"\nCandle age: "+(now>0&&close<=now?age(now-close):"unverified")+" · "+(a.historyFresh(now)?"within freshness window":"stale / time unverified");
        }
        if(a!=null&&!a.candles.isEmpty()){
            int gaps=historyGaps(f,a);
            s+="\nInterval gaps in up to 48 recent steps: "+gaps+" (can include market closures)";
        }
        Integer depth=repo.providerDepth.get(f);
        s+="\nProvider returned: "+(depth==null?"not verified this session":depth+" closed bars of "+repo.requestedDepth.getOrDefault(f,MarketRepository.historyLimit(f))+" requested");
        s+="\nLast successful fetch: "+(sync>0?EXACT.format(Instant.ofEpochMilli(sync)):"none");
        s+="\nVerified since app process start: "+(repo.liveSynced.containsKey(f)?"yes":"no");if(error!=null)s+="\nFetch error: "+error;return s;
    }
    private final Map<Market.Frame,ZoneEngine.Analysis> gapSources=new EnumMap<>(Market.Frame.class);
    private final Map<Market.Frame,Integer> gaps=new EnumMap<>(Market.Frame.class);
    private int historyGaps(Market.Frame f,ZoneEngine.Analysis a){
        if(gapSources.get(f)!=a){gapSources.put(f,a);gaps.put(f,HistoryQuality.gaps(a.candles,f,48));}
        return gaps.getOrDefault(f,0);
    }
    private void requestMonitoring(){
        if(!MonitorNotifications.permission(this)&&Build.VERSION.SDK_INT>=33){startAfterPermission=true;requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},230);return;}
        try{startForegroundService(new Intent(this,MonitoringService.class));}catch(IllegalStateException|SecurityException e){runtime.settings.edit().putString("stop-reason","Android did not allow background monitoring to start; reopen and try again").apply();liveUpdate();Toast.makeText(this,"Could not start monitoring; check Android restrictions",Toast.LENGTH_LONG).show();}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==230){boolean start=startAfterPermission;startAfterPermission=false;if(start&&MonitorNotifications.permission(this)&&resumed)requestMonitoring();else Toast.makeText(this,"Allow notifications to start visible background monitoring",Toast.LENGTH_LONG).show();}
        if(request==230||request==231){runtime.refreshPermissions();runtime.refreshState();}
    }
    private void heading(String title,String sub){body.addView(label(title,20,TEXT,true));space(5);body.addView(label(sub,11,MUTED,false));space(14);}
    private void space(int n){gap(body,n);}
    private void gap(LinearLayout parent,int n){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(n)));}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout card(int padding){LinearLayout c=column();c.setPadding(dp(padding),dp(padding),dp(padding),dp(padding));c.setBackground(background(SURFACE,14));return c;}
    private TextView label(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1);t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));return t;}
    private Button button(String text,boolean selected){Button b=new Button(this);b.setText(text);b.setTextSize(12);b.setMinHeight(controlHeight(44));b.setAllCaps(false);b.setTextColor(selected?BG:MUTED);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setBackground(background(selected?GOLD:SURFACE,10));b.setPadding(dp(8),dp(6),dp(8),dp(6));b.setMinHeight(controlHeight(44));b.setMinimumHeight(controlHeight(44));b.setMinWidth(0);b.setMinimumWidth(0);return b;}
    private Button small(String title,String description,int width){Button b=button(title,false);b.setContentDescription(description);b.setLayoutParams(new LinearLayout.LayoutParams(dp(width),controlHeight(44)));return b;}
    private View line(){View v=new View(this);v.setBackgroundColor(BORDER);return v;}
    private GradientDrawable background(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(color==SURFACE)g.setStroke(dp(1),BORDER);return g;}
    private int controlHeight(int n){return dp(n*Math.max(1f,getResources().getConfiguration().fontScale));}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private static void set(TextView t,String s){if(t!=null&&!s.contentEquals(t.getText()))t.setText(s);}
    static String money(double p){return PriceFormat.display(p);}
    static String age(long ms){long s=ms/1000;if(s<60)return s+"s";if(s<3600)return s/60+"m";if(s<86400)return s/3600+"h";return s/86400+"d";}
}
