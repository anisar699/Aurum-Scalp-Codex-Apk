package com.aurum.scalp;
import java.util.*;
/** Process-wide provider budget, monotonic deadlines and in-flight reservations survive UI sessions. */
final class PollPolicy {
 static final long MAX_RETRY=3600000L;
 private final Map<String,Long> due=new HashMap<>();
 private final Set<String> running=new HashSet<>();
 private final Map<String,Object> owners=new HashMap<>();
 private long limitedUntil;
 synchronized boolean claim(String key,long now,boolean manual){return claim(key,now,manual,this);}
 synchronized boolean claim(String key,long now,boolean manual,Object owner){
  if(now<limitedUntil||running.contains(key)||(!manual&&now<due.getOrDefault(key,0L)))return false;
  running.add(key);owners.put(key,owner);return true;
 }
 synchronized void complete(String key,long now,long delay,boolean limited){
  long deadline=now+Math.min(MAX_RETRY,Math.max(0,delay));
  due.put(key,deadline);if(limited)limitedUntil=Math.max(limitedUntil,deadline);running.remove(key);owners.remove(key);
 }
 synchronized void marketOpened(){due.remove("quote");for(Market.Frame frame:Market.Frame.values())due.remove(frame.name());}
 synchronized void releaseOwner(Object owner){for(String k:new ArrayList<>(owners.keySet()))if(owners.get(k)==owner){owners.remove(k);running.remove(k);}}
 synchronized void release(String key){running.remove(key);owners.remove(key);}
 synchronized void restore(long deadline,long now){limitedUntil=Math.max(limitedUntil,Math.min(deadline,now+MAX_RETRY));}
 synchronized long remaining(long now){return Math.max(0,limitedUntil-now);}
}
