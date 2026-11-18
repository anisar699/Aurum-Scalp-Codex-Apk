package com.aurum.scalp;
/** HTTPS Date anchor advanced only by monotonic elapsed time. Tick age never sets the clock. */
final class MarketClock {
 private long server,elapsed;
 synchronized void sync(long date,long mono){if(date>0){server=date;elapsed=mono;}}
 synchronized long now(long mono){return server>0&&mono>=elapsed&&mono-elapsed<86400000L?server+(mono-elapsed):0;}
}
