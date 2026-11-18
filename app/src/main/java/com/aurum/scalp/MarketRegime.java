package com.aurum.scalp;

import java.util.*;

/** Descriptive, closed-candle measurements. Thresholds are rules, not a forecast model. */
public final class MarketRegime {
    private MarketRegime() {}
    public static final class Readout {
        public final boolean available;
        public final String trend,volatility;
        public final double adx,efficiency,atr,atrRatio,rangeLow,rangeHigh,position;
        Readout(boolean ok,String trend,String vol,double adx,double er,double atr,double ratio,double low,double high,double pos){
            available=ok;this.trend=trend;volatility=vol;this.adx=adx;efficiency=er;this.atr=atr;atrRatio=ratio;rangeLow=low;rangeHigh=high;position=pos;
        }
    }
    public static Readout calculate(ZoneEngine.Analysis a){
        if(a==null||a.candles.size()<80||a.atr<=0)return new Readout(false,"Need 80 closed candles","Unavailable",0,0,0,0,0,0,0);
        List<Market.Candle> b=a.candles;int n=b.size();double[] tr=ZoneEngine.atrSeries(b);
        double[] baseline=Arrays.copyOfRange(tr,Math.max(14,n-101),n-1);Arrays.sort(baseline);
        double median=baseline[baseline.length/2],ratio=a.atr/Math.max(.00001,median);
        double adx=adx(b),travel=0;for(int i=n-20;i<n;i++)travel+=Math.abs(b.get(i).close-b.get(i-1).close);
        double er=travel>0?Math.abs(b.get(n-1).close-b.get(n-21).close)/travel:0;
        double slope=(a.ema20-ZoneEngine.ema(b.subList(0,n-4),20))/a.atr;
        String trend="Transition / mixed";
        if(adx>=25&&a.ema20>a.ema50&&slope>=.10)trend="Uptrend";
        else if(adx>=25&&a.ema20<a.ema50&&slope<=-.10)trend="Downtrend";
        else if(adx<20&&er<.35)trend="Range";
        double low=Double.MAX_VALUE,high=0;for(int i=n-20;i<n;i++){low=Math.min(low,b.get(i).low);high=Math.max(high,b.get(i).high);}
        double position=high>low?100*(b.get(n-1).close-low)/(high-low):50;
        return new Readout(true,trend,ratio<.75?"Low":ratio>1.5?"High":"Normal",adx,er,a.atr,ratio,low,high,position);
    }
    static double adx(List<Market.Candle> b){
        double tr=0,plus=0,minus=0,value=0;int samples=0;
        for(int i=1;i<b.size();i++){
            Market.Candle c=b.get(i),p=b.get(i-1);double up=c.high-p.high,down=p.low-c.low;
            double range=Math.max(c.high-c.low,Math.max(Math.abs(c.high-p.close),Math.abs(c.low-p.close)));
            double pdm=up>down&&up>0?up:0,mdm=down>up&&down>0?down:0;
            if(i<=14){tr+=range;plus+=pdm;minus+=mdm;}else{tr=tr-tr/14+range;plus=plus-plus/14+pdm;minus=minus-minus/14+mdm;}
            if(i>=14){double diSum=plus+minus,dx=tr>0&&diSum>0?100*Math.abs(plus-minus)/diSum:0;samples++;value=samples<=14?(value*(samples-1)+dx)/samples:(value*13+dx)/14;}
        }
        return value;
    }
}
