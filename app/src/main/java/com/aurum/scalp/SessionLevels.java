package com.aurum.scalp;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class SessionLevels {
    private static final ZoneId NEW_YORK=ZoneId.of("America/New_York");
    private static final ZoneId LONDON=ZoneId.of("Europe/London");
    private static final long FIVE=Market.Frame.M5.duration(), HOUR=Market.Frame.H1.duration();
    private static final double WICK_BUFFER=.05;

    record Level(String label,double price,boolean high,long availableAt) {}
    record Sweep(long barTime,Level level) {}
    record Snapshot(List<Level> levels,List<Sweep> sweeps) {}
    private record Window(long start,long end) {}
    private record Range(double low,double high) {}

    private final Map<Long,Market.Candle> five;
    private final Map<Long,Market.Candle> hour;
    private final Map<LocalDate,Optional<Range>> days=new HashMap<>();
    private final Map<LocalDate,Optional<Range>> weeks=new HashMap<>();

    private SessionLevels(List<Market.Candle> five,List<Market.Candle> hour) {
        this.five=index(five);
        this.hour=index(hour);
    }

    static Snapshot calculate(List<Market.Candle> five,List<Market.Candle> hour,long cutoff) {
        if(cutoff<=0)return new Snapshot(List.of(),List.of());
        List<Market.Candle> minutes=Market.closed(five,FIVE,cutoff);
        SessionLevels book=new SessionLevels(minutes,Market.closed(hour,HOUR,cutoff));
        LocalDate tradeDate=tradeDate(cutoff), intraday=weekdayOnOrBefore(tradeDate);
        List<Level> current=book.levels(intraday,weekdayOnOrBefore(tradeDate.minusDays(1)),cutoff);
        return new Snapshot(List.copyOf(current),List.copyOf(book.sweeps(minutes,cutoff)));
    }

    private List<Level> levels(LocalDate intraday,LocalDate priorDay,long cutoff) {
        List<Level> levels=new ArrayList<>(10);
        Window day=tradingHours(priorDay);
        if(day.end<=cutoff)dayRange(priorDay).ifPresent(r->add(levels,r,"PDH","PDL",day.end));

        LocalDate friday=priorDay;
        while(friday.getDayOfWeek()!=DayOfWeek.FRIDAY)friday=friday.minusDays(1);
        long weekEnd=tradingHours(friday).end;
        if(weekEnd<=cutoff)weekRange(friday).ifPresent(r->add(levels,r,"PWH","PWL",weekEnd));

        addOpening(levels,window(intraday,0,0,6,0,ZoneOffset.UTC),"Asia H","Asia L",cutoff);
        addOpening(levels,window(intraday,8,0,9,0,LONDON),"London OR H","London OR L",cutoff);
        addOpening(levels,window(intraday,8,0,9,0,NEW_YORK),"NY OR H","NY OR L",cutoff);
        return levels;
    }

    private void addOpening(List<Level> levels,Window window,String high,String low,long cutoff) {
        if(window.end>cutoff)return;
        Range range=exact(five,window,FIVE);
        if(range!=null)add(levels,range,high,low,window.end);
    }

    private static void add(List<Level> levels,Range range,String high,String low,long availableAt) {
        levels.add(new Level(high,range.high,true,availableAt));
        levels.add(new Level(low,range.low,false,availableAt));
    }

    private Optional<Range> dayRange(LocalDate date) {
        return days.computeIfAbsent(date,d->{
            Window window=tradingHours(d);
            Range hourly=exact(hour,window,HOUR);
            return Optional.ofNullable(hourly==null?exact(five,window,FIVE):hourly);
        });
    }

    private Optional<Range> weekRange(LocalDate friday) {
        return weeks.computeIfAbsent(friday,end->{
            double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
            for(LocalDate date=end.minusDays(4);!date.isAfter(end);date=date.plusDays(1)){
                Range range=dayRange(date).orElse(null);
                if(range==null)return Optional.empty();
                low=Math.min(low,range.low);
                high=Math.max(high,range.high);
            }
            return Optional.of(new Range(low,high));
        });
    }

    private List<Sweep> sweeps(List<Market.Candle> candles,long cutoff) {
        List<Sweep> result=new ArrayList<>();
        Map<LocalDate,List<Level>> available=new HashMap<>();
        for(int i=1;i<candles.size();i++){
            Market.Candle previous=candles.get(i-1), bar=candles.get(i);
            if(Market.closeTime(previous,FIVE)!=bar.time)continue;
            LocalDate date=tradeDate(bar.time);
            Window hours=tradingHours(date);
            if(!date.equals(weekdayOnOrBefore(date))||previous.time<hours.start||Market.closeTime(bar,FIVE)>hours.end)continue;
            List<Level> levels=available.computeIfAbsent(date,d->levels(d,weekdayOnOrBefore(d.minusDays(1)),cutoff));
            Level low=null,high=null;
            for(Level level:levels){
                if(level.availableAt>bar.time)continue;
                double edge=level.price;
                if(level.high&&previous.close<=edge&&bar.open<=edge
                    &&bar.high>=edge+WICK_BUFFER&&bar.close<edge&&(high==null||edge>high.price))high=level;
                if(!level.high&&previous.close>=edge&&bar.open>=edge
                    &&bar.low<=edge-WICK_BUFFER&&bar.close>edge&&(low==null||edge<low.price))low=level;
            }
            if(low!=null)result.add(new Sweep(bar.time,low));
            if(high!=null)result.add(new Sweep(bar.time,high));
        }
        return result;
    }

    private static Range exact(Map<Long,Market.Candle> candles,Window window,long duration) {
        double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(long t=window.start;t<window.end;t+=duration){
            Market.Candle bar=candles.get(t);
            if(bar==null||Market.closeTime(bar,duration)!=t+duration)return null;
            low=Math.min(low,bar.low);
            high=Math.max(high,bar.high);
        }
        return new Range(low,high);
    }

    private static Map<Long,Market.Candle> index(List<Market.Candle> candles) {
        Map<Long,Market.Candle> indexed=new HashMap<>();
        for(Market.Candle bar:candles)indexed.put(bar.time,bar);
        return indexed;
    }

    private static LocalDate tradeDate(long time) {
        return LocalDate.ofEpochDay(Market.tradingDay(time));
    }

    private static LocalDate weekdayOnOrBefore(LocalDate date) {
        while(date.getDayOfWeek()==DayOfWeek.SATURDAY||date.getDayOfWeek()==DayOfWeek.SUNDAY)date=date.minusDays(1);
        return date;
    }

    private static Window tradingHours(LocalDate date) {
        return new Window(at(date.minusDays(1),18,0,NEW_YORK),at(date,17,0,NEW_YORK));
    }

    private static Window window(LocalDate date,int fromHour,int fromMinute,int toHour,int toMinute,ZoneId zone) {
        return new Window(at(date,fromHour,fromMinute,zone),at(date,toHour,toMinute,zone));
    }

    private static long at(LocalDate date,int hour,int minute,ZoneId zone) {
        return date.atTime(LocalTime.of(hour,minute)).atZone(zone).toInstant().toEpochMilli();
    }

    static final class Cache {
        private ZoneEngine.Analysis five,hour;
        private long bucket=Long.MIN_VALUE;
        private Snapshot snapshot;

        Snapshot get(Map<Market.Frame,ZoneEngine.Analysis> analyses,long now) {
            ZoneEngine.Analysis m5=analyses.get(Market.Frame.M5),h1=analyses.get(Market.Frame.H1);
            long cutoff=now>0?now:Math.max(DataFreshness.lastClose(m5),DataFreshness.lastClose(h1));
            if(m5!=five||h1!=hour||cutoff/FIVE!=bucket){
                five=m5;
                hour=h1;
                bucket=cutoff/FIVE;
                snapshot=calculate(m5==null?List.of():m5.candles,h1==null?List.of():h1.candles,cutoff);
            }
            return snapshot;
        }
    }
}
