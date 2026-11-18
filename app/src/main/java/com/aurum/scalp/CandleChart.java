package com.aurum.scalp;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class CandleChart extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final DashPathEffect dash;
    private final RectF box=new RectF();
    private final List<ZoneEngine.Zone> painted=new ArrayList<>();
    private ZoneEngine.Analysis analysis;
    private double price;
    private int visible=48,offset=0,first,last,downOffset;
    private long selectedTime=-1;
    private float left,right,top,bottom,downX,downY;
    private double low,high;
    private boolean dragging;
    private static final Typeface SANS=Typeface.create("sans-serif",Typeface.NORMAL);
    private static final DateTimeFormatter SHORT=DateTimeFormatter.ofPattern("dd MMM HH:mm",Locale.US).withZone(ZoneOffset.UTC);
    public CandleChart(Context context){super(context);density=getResources().getDisplayMetrics().density;dash=new DashPathEffect(new float[]{dp(4),dp(3)},0);setFocusable(true);describe();}
    private int dp(float n){return Math.round(n*density);}
    private int selectedIndex(){if(analysis!=null)for(int i=0;i<analysis.candles.size();i++)if(analysis.candles.get(i).time==selectedTime)return i;return -1;}
    private void describe(){
        String description="XAUUSD candle chart. "+(offset>0?"Viewing history. ":"Latest candles. ")+"Tap a candle for OHLC. Green support and coral resistance; bands contain observed swing prices.";
        int selected=selectedIndex();if(selected>=0){Market.Candle b=analysis.candles.get(selected);description="Selected candle "+SHORT.format(Instant.ofEpochMilli(b.time))+String.format(Locale.US," UTC. Open %.2f, high %.2f, low %.2f, close %.2f. ",b.open,b.high,b.low,b.close)+description;}
        setContentDescription(description);
    }
    public void data(ZoneEngine.Analysis a,double bid){
        long anchor=-1;
        if(analysis!=null&&analysis.frame!=a.frame){offset=0;selectedTime=-1;}
        else if(analysis!=null&&offset>0&&!analysis.candles.isEmpty())anchor=analysis.candles.get(Math.max(0,analysis.candles.size()-offset-1)).time;
        analysis=a;price=bid;
        if(anchor>=0){int end=0;while(end<a.candles.size()&&a.candles.get(end).time<=anchor)end++;offset=a.candles.size()-end;}
        offset=Math.min(offset,Math.max(0,a.candles.size()-visible));if(selectedIndex()<0)selectedTime=-1;describe();refreshZones();invalidate();
    }
    public void price(double bid){price=bid;refreshZones();invalidate();}
    private void refreshZones(){painted.clear();if(analysis!=null){painted.addAll(analysis.nearest(price,true,3,true));painted.addAll(analysis.nearest(price,false,3,true));}}
    public void zoom(boolean in){visible=Math.max(24,Math.min(120,visible+(in?-12:12)));if(analysis!=null)offset=Math.min(offset,Math.max(0,analysis.candles.size()-visible));describe();invalidate();}
    public void latest(){offset=0;selectedTime=-1;describe();invalidate();}
    private float y(double v){return bottom-(float)((v-low)/(high-low))*(bottom-top);}
    private void text(Canvas c,String s,float x,float y,int size,int color){p.setColor(color);p.setTextSize(dp(size));p.setTypeface(SANS);c.drawText(s,x,y,p);}
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(analysis==null||analysis.candles.isEmpty()){text(c,"Waiting for market history",dp(12),getHeight()/2f,13,MainActivity.MUTED);return;}
        p.setTextSize(dp(10));p.setTypeface(SANS);String bidLabel=String.format(Locale.US,"%,.2f",price);
        left=dp(4);right=getWidth()-Math.max(dp(70),p.measureText(bidLabel)+dp(12));top=dp(30);bottom=getHeight()-dp(27);if(right<=left)return;
        last=analysis.candles.size()-Math.min(offset,Math.max(0,analysis.candles.size()-1));first=Math.max(0,last-visible);low=Double.MAX_VALUE;high=0;
        for(int i=first;i<last;i++){Market.Candle b=analysis.candles.get(i);low=Math.min(low,b.low);high=Math.max(high,b.high);}
        if(offset==0&&price>0){low=Math.min(low,price);high=Math.max(high,price);}
        double pad=Math.max(analysis.atr*.35,(high-low)*.04);low-=pad;high+=pad;if(high<=low)high=low+1;
        float priceY=price>=low&&price<=high?y(price):-9999;
        String format=analysis.frame.scalp()?"%,.2f":"%,.1f";
        for(int i=0;i<5;i++){double v=low+(high-low)*i/4;float yy=y(v);p.setColor(MainActivity.BORDER);p.setStrokeWidth(dp(.5f));c.drawLine(left,yy,right,yy,p);if(Math.abs(yy-priceY)>dp(14))text(c,String.format(Locale.US,format,v),right+dp(7),yy+dp(4),10,MainActivity.MUTED);}
        c.save();c.clipRect(left,top,right,bottom);
        for(ZoneEngine.Zone z:painted){int color=z.reference?MainActivity.GOLD:z.support?MainActivity.GREEN:MainActivity.RED;p.setColor((color&0xFFFFFF)|0x24000000);c.drawRect(left,y(z.high),right,y(z.low),p);p.setColor((color&0xFFFFFF)|0x99000000);p.setStrokeWidth(dp(1));p.setPathEffect(dash);c.drawLine(left,y(z.center),right,y(z.center),p);p.setPathEffect(null);}
        float step=(right-left)/Math.max(1,last-first);
        for(int i=first;i<last;i++){Market.Candle b=analysis.candles.get(i);float x=left+(i-first+.5f)*step;p.setColor(b.close>=b.open?MainActivity.GREEN:MainActivity.RED);p.setStrokeWidth(dp(1));c.drawLine(x,y(b.high),x,y(b.low),p);float t=y(Math.max(b.open,b.close)),d=y(Math.min(b.open,b.close));c.drawRect(x-step*.29f,t,x+step*.29f,Math.max(t+dp(1),d),p);}
        if(price>=low&&price<=high){p.setColor(MainActivity.GOLD);p.setStrokeWidth(dp(1));p.setPathEffect(dash);c.drawLine(left,y(price),right,y(price),p);p.setPathEffect(null);}
        int selected=selectedIndex();
        if(selected>=first&&selected<last){float x=left+(selected-first+.5f)*step;p.setColor(MainActivity.TEXT);p.setStrokeWidth(dp(1));c.drawLine(x,top,x,bottom,p);p.setColor(MainActivity.GOLD);c.drawCircle(x,y(analysis.candles.get(selected).close),dp(3),p);}
        c.restore();
        if(price>=low&&price<=high){p.setColor(MainActivity.GOLD);box.set(right+dp(2),y(price)-dp(10),getWidth(),y(price)+dp(10));c.drawRoundRect(box,dp(4),dp(4),p);text(c,bidLabel,right+dp(5),y(price)+dp(4),10,MainActivity.BG);}
        String start=SHORT.format(Instant.ofEpochMilli(analysis.candles.get(first).time)),end=SHORT.format(Instant.ofEpochMilli(analysis.candles.get(last-1).time));text(c,start,left,bottom+dp(20),9,MainActivity.MUTED);p.setTextSize(dp(9));text(c,end,right-p.measureText(end),bottom+dp(20),9,MainActivity.MUTED);
        if(selected>=0&&selected<analysis.candles.size()){
            Market.Candle b=analysis.candles.get(selected);text(c,String.format(Locale.US,"O %.2f  H %.2f  L %.2f  C %.2f",b.open,b.high,b.low,b.close),left,dp(14),9,MainActivity.TEXT);
        }else text(c,"CLOSED "+analysis.frame.label+"  ·  UTC"+(offset>0?"  ·  HISTORY":""),left,dp(14),10,MainActivity.MUTED);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(analysis==null||analysis.candles.isEmpty())return false;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:downX=e.getX();downY=e.getY();downOffset=offset;dragging=false;return true;
            case MotionEvent.ACTION_MOVE:
                float dx=e.getX()-downX,dy=e.getY()-downY;
                if(Math.abs(dx)>dp(12)&&Math.abs(dx)>Math.abs(dy)){dragging=true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);offset=Math.max(0,Math.min(Math.max(0,analysis.candles.size()-visible),downOffset+(int)(dx/Math.max(1,right-left)*visible)));selectedTime=-1;describe();invalidate();}return true;
            case MotionEvent.ACTION_UP:
                if(!dragging){int selected=Math.max(first,Math.min(last-1,first+(int)((e.getX()-left)/Math.max(1,right-left)*(last-first))));if(selected>=0&&selected<analysis.candles.size())selectedTime=analysis.candles.get(selected).time;describe();performClick();invalidate();}if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;
            case MotionEvent.ACTION_CANCEL:if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
