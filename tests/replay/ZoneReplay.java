package com.aurum.scalp;

import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/** Offline, chronological probe. A future bar never participates in choosing its test zone. */
public final class ZoneReplay {
    private static final int WARMUP = 80, HORIZON = 6;
    private static final class Score {
        int windows, covered, touched, reacted, invalidated, noDecision, gapSkipped;
        final List<Integer> delay = new ArrayList<>();
        String output(){
            List<Integer> sorted = delay.stream().sorted().collect(Collectors.toList());
            return String.format(Locale.US,
                "windows=%d covered=%d touches=%d reactions=%d invalidated=%d unresolved=%d skipped-gaps=%d median-bars-after-touch=%s",
                windows,covered,touched,reacted,invalidated,noDecision,gapSkipped,
                sorted.isEmpty()?"n/a":String.valueOf(sorted.get((sorted.size()-1)/2)));
        }
    }
    public static void main(String[] inputs) throws Exception {
        System.out.println("Non-overlapping six-closed-bar windows. Nearest confirmed zone on either side; first touch; 0.5 ATR close away versus two buffered closes beyond the band.");
        System.out.println("Median delay is bars from touch to reaction, not app or provider notification latency.");
        for(String file:inputs) {
            Path path=Path.of(file);
            String[] label=path.getFileName().toString().split("__");
            Market.Frame frame=Market.Frame.valueOf(label[1].split("\\.")[0]);
            List<Market.Candle> bars=new ArrayList<>();
            for(String row:Files.readAllLines(path)) {
                String[] c=row.split(",");long time=Long.parseLong(c[0]);
                bars.add(new Market.Candle(time,Double.parseDouble(c[1]),Double.parseDouble(c[2]),Double.parseDouble(c[3]),Double.parseDouble(c[4]),time+frame.duration()));
            }
            Map<String,Score> groups=new TreeMap<>();
            for(int start=WARMUP; start+HORIZON<=bars.size(); start+=HORIZON){
                long now=bars.get(start-1).endTime;
                ZoneEngine.Analysis a=ZoneEngine.calculate(bars.subList(0,start),frame,now);
                MarketRegime.Readout regime=MarketRegime.calculate(a);
                Score score=groups.computeIfAbsent(regime.trend+" / "+regime.volatility,k->new Score());
                if(!ZoneEngine.continuous(bars,start-1,start+HORIZON-1,frame.duration())){score.gapSkipped++;continue;}
                score.windows++;
                double price=bars.get(start-1).close;
                List<ZoneEngine.Zone> around=new ArrayList<>(ZoneSelection.confirmed(a,price,true,1,ZoneSelection.Distance.ALL));
                around.addAll(ZoneSelection.confirmed(a,price,false,1,ZoneSelection.Distance.ALL));
                if(around.isEmpty())continue;
                score.covered++;
                ZoneEngine.Zone z=around.stream().min(Comparator.comparingDouble(x->x.distance(price))).orElseThrow();
                int touch=-1,beyond=0;boolean done=false;
                for(int i=start;i<start+HORIZON;i++){
                    Market.Candle c=bars.get(i);
                    if(touch<0 && c.low<=z.high && c.high>=z.low){touch=i;score.touched++;}
                    if(touch<0)continue;
                    double margin=Math.max(.05,a.atr*.05);
                    beyond=(z.support?c.close<z.low-margin:c.close>z.high+margin)?beyond+1:0;
                    if(beyond>=2){score.invalidated++;done=true;break;}
                    // For the touch candle, the close may already have reacted. The subsequent close is used conservatively.
                    if(i>touch && (z.support?c.close>=z.high+a.atr*.5:c.close<=z.low-a.atr*.5)){
                        score.reacted++;score.delay.add(i-touch);done=true;break;
                    }
                }
                if(touch>=0&&!done)score.noDecision++;
            }
            System.out.println(label[0]+" "+frame.label+" closed="+bars.size()+" gaps="+HistoryQuality.gaps(bars,frame,bars.size()));
            for(Map.Entry<String,Score> result:groups.entrySet())System.out.println("  "+result.getKey()+" "+result.getValue().output());
        }
    }
}
