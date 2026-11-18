package com.aurum.scalp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

final class PriceFormat {
    private PriceFormat(){}
    static String display(double value){return String.format(Locale.US,"%,.2f",value);}
    static String low(double value){return edge(value,RoundingMode.FLOOR);}
    static String high(double value){return edge(value,RoundingMode.CEILING);}
    private static String edge(double value,RoundingMode mode){
        return BigDecimal.valueOf(value).setScale(2,mode).toPlainString();
    }
}
