package com.aurum.scalp;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Explicit provider rate limits cannot be bypassed by manual refresh. */
final class RetryGate {
    private long next;
    private boolean rateLimited;
    synchronized boolean eligible(long now){return now>=next;}
    synchronized void failure(long now,long delay,boolean limited){next=now+Math.min(3_600_000L,Math.max(0,delay));rateLimited=limited;}
    synchronized void success(){next=0;rateLimited=false;}
    synchronized void manualRefresh(){if(!rateLimited)next=0;}
    static long retryAfter(String header,long now){
        if(header!=null)try{return Math.min(3_600_000L,Math.max(60_000,new java.math.BigInteger(header.trim()).max(java.math.BigInteger.ZERO).min(java.math.BigInteger.valueOf(3600)).longValue()*1000));}catch(Exception ignored){
            try{return Math.min(3_600_000L,Math.max(60_000,ZonedDateTime.parse(header,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now));}catch(Exception ignoredDate){}
        }
        return 60_000;
    }
}
