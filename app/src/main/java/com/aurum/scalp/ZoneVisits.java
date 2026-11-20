package com.aurum.scalp;

/** Observes retests of an existing band after a confirmed departure on its valid side. */
final class ZoneVisits {
    private static final int CONFIRM_WITHIN = 3;
    private int touch = -1, lastDefended = -1, defended, failed;
    private double touchLow, touchHigh, touchAtr;
    private boolean armed;

    int defended(){return defended;}
    int failed(){return failed;}
    int lastDefended(){return lastDefended;}

    void reset(){
        armed=false;
        touch=-1;
        lastDefended=-1;
        defended=failed=0;
    }

    void observe(int index, Market.Candle bar, double previousClose, boolean adjacent,
                 double low, double high, boolean support, double atr){
        if(!adjacent){
            touch=-1;
            armed=false;
        }else if(armed&&bar.low<=high&&bar.high>=low&&(support?previousClose>=high:previousClose<=low)){
            touch=index;
            touchLow=low;
            touchHigh=high;
            touchAtr=atr;
            armed=false;
        }
        if(touch>=0){
            double displacement=support?bar.close-touchHigh:touchLow-bar.close;
            double beyond=support?touchLow-bar.close:bar.close-touchHigh;
            if(displacement>=.25*touchAtr){
                defended++;
                lastDefended=index;
                touch=-1;
            }else if(beyond>Math.max(.05,.05*touchAtr)){
                failed++;
                touch=-1;
            }else if(index-touch>=CONFIRM_WITHIN){
                touch=-1;
            }
        }
        double distance=support?bar.close-high:low-bar.close;
        if(touch<0&&distance>.35*atr)armed=true;
    }
}
