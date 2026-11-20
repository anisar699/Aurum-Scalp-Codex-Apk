(function(root){'use strict';
 const Core={
  rows(input){const map=new Map();for(const r of input||[]){if(r.length<5||!r.slice(0,5).every(Number.isFinite)||r[0]<=0||r[3]<=0||r[2]<Math.max(r[1],r[4])||r[3]>Math.min(r[1],r[4]))continue;map.set(r[0],{time:r[0],open:r[1],high:r[2],low:r[3],close:r[4],kind:r[5]||'closed'});}return [...map.values()].sort((a,b)=>a.time-b.time);},
  combine(closed,forming,on){const last=closed.length?closed[closed.length-1].time:0;return on?closed.concat(Core.rows(forming).filter(b=>b.time>last)):closed.slice();},
  equal(a,b){return a&&b&&a.time===b.time&&a.open===b.open&&a.high===b.high&&a.low===b.low&&a.close===b.close&&a.kind===b.kind;},
  incremental(old,bars){if(!old.length||bars.length<old.length)return false;for(let i=0;i<old.length-1;i++)if(!Core.equal(old[i],bars[i]))return false;return bars.length>=old.length&&old[old.length-1].time===bars[old.length-1].time;},
  ema(bars,period){if(bars.length<period)return [];let v=bars[0].close,k=2/(period+1);return bars.map((b,i)=>{v=i===0?v:v+(b.close-v)*k;return {time:b.time,value:v};}).slice(period-1);},
  scale(range,bands,p){let lo=range.minValue,hi=range.maxValue,span=Math.max(.01,hi-lo);let below=null,above=null,inside=null;for(const b of bands){if(b.low<=p&&b.high>=p&&(!inside||b.high-b.low<inside.high-inside.low))inside=b;if(b.high<=p&&(!below||b.high>below.high))below=b;if(b.low>=p&&(!above||b.low<above.low))above=b;}
   for(const b of [inside,below,above])if(b){let l=Math.min(lo,b.low),h=Math.max(hi,b.high);if(h-l<=span*1.8&&Math.max(0,lo-b.low,b.high-hi)<=span*.6){lo=l;hi=h;}}return {minValue:lo,maxValue:hi};},
  distance(b,p){return Math.max(0,b.low-p,p-b.high);},
  pickBand(bands,price,tolerance){return bands.filter(b=>Core.distance(b,price)<=tolerance).sort((a,b)=>Core.distance(a,price)-Core.distance(b,price)||(a.macro-b.macro)||((a.high-a.low)-(b.high-b.low)))[0];},
  labels(items,priceY,height,gap=20){let used=priceY==null?[]:[priceY],chosen=new Set();for(const it of items.slice().sort((a,b)=>b.priority-a.priority)){if(chosen.size>=6)break;if(it.y!=null&&it.y>=0&&it.y<=height&&used.every(y=>Math.abs(y-it.y)>=gap)){chosen.add(it.id);used.push(it.y);}}return chosen;},
  range(old,bars,r){if(!r||!old.length||!bars.length)return null;const width=r.to-r.from;if(r.to>=old.length-1){const to=bars.length-1+(r.to-(old.length-1));return {from:to-width,to};}
   const i=Math.max(0,Math.min(old.length-1,Math.floor(r.from))),t=old[i].time;let j=bars.findIndex(b=>b.time>=t);if(j<0)j=bars.length-1;const from=j+(r.from-i);return {from,to:from+width};}
 };
 if(typeof module!=='undefined'&&module.exports){module.exports=Core;return;}
 const LC=root.LightweightCharts,plot=document.getElementById('plot'),legend=document.getElementById('legend'),status=document.getElementById('status');let closed=[],bars=[],payload={},bands=[],lines=[],selected=null,selectedPrice=null,frame=null,queued=false,emaKey='',bandKey='',lastFont=0,restoring=false,focusMode=false,activeZone=null,zoneInfoKey='';
 const color=b=>b.watch?'#e9c981':b.support?'#7bdcb6':'#ff9e9a';
 const chart=LC.createChart(plot,{autoSize:true,layout:{background:{type:'solid',color:'#0b1118'},textColor:'#a5b5c6',fontSize:11,attributionLogo:true},grid:{vertLines:{color:'#1a2835'},horzLines:{color:'#1a2835'}},rightPriceScale:{borderColor:'#273545',scaleMargins:{top:.14,bottom:.10}},timeScale:{timeVisible:true,secondsVisible:false,rightOffset:6,borderColor:'#273545'},crosshair:{mode:LC.CrosshairMode.Normal},localization:{priceFormatter:p=>p.toFixed(2)},handleScroll:{mouseWheel:true,pressedMouseMove:true,horzTouchDrag:true,vertTouchDrag:true},handleScale:{mouseWheel:true,pinch:true,axisPressedMouseMove:{time:true,price:true},axisDoubleClickReset:{time:true,price:true}}});
 const series=chart.addSeries(LC.CandlestickSeries,{upColor:'#7bdcb6',downColor:'#ff9e9a',wickUpColor:'#7bdcb6',wickDownColor:'#ff9e9a',borderVisible:false,priceLineVisible:false,lastValueVisible:false,priceFormat:{type:'price',precision:2,minMove:.01},autoscaleInfoProvider:base=>{const a=base();return a&&a.priceRange&&payload.nearOn&&!focusMode?{...a,priceRange:Core.scale(a.priceRange,bands,payload.price)}:a;}});
 const ema50=chart.addSeries(LC.LineSeries,{color:'#f1f4f4',lineWidth:1,priceLineVisible:false,lastValueVisible:false,crosshairMarkerVisible:false,autoscaleInfoProvider:base=>focusMode?null:base()}),ema200=chart.addSeries(LC.LineSeries,{color:'#e9c981',lineWidth:1,lineStyle:LC.LineStyle.Dashed,priceLineVisible:false,lastValueVisible:false,crosshairMarkerVisible:false,autoscaleInfoProvider:base=>focusMode?null:base()});
 const priceLine=series.createPriceLine({price:0,color:'#e9c981',lineStyle:LC.LineStyle.Dashed,lineWidth:1,lineVisible:false,axisLabelVisible:false,title:'MID'});
 const primitive={attached(p){this.request=p.requestUpdate;},detached(){this.request=null;},updateAllViews(){},paneViews(){return [{zOrder:()=> 'bottom',renderer:()=>({draw(target){target.useBitmapCoordinateSpace(s=>{const ctx=s.context,w=s.bitmapSize.width;for(const b of bands){const a=series.priceToCoordinate(b.high),z=series.priceToCoordinate(b.low);if(a==null||z==null)continue;const y=a*s.verticalPixelRatio,h=Math.max(1,(z-a)*s.verticalPixelRatio);ctx.fillStyle=b.watch?'rgba(233,201,129,.10)':b.support?'rgba(123,220,182,.13)':'rgba(255,158,154,.13)';ctx.fillRect(0,y,w,h);ctx.strokeStyle=color(b);ctx.globalAlpha=b.macro?.55:.75;ctx.lineWidth=s.verticalPixelRatio;ctx.setLineDash(b.macro||b.watch?[5*s.horizontalPixelRatio,4*s.horizontalPixelRatio]:[]);ctx.beginPath();ctx.moveTo(0,y+h/2);ctx.lineTo(w,y+h/2);ctx.stroke();ctx.globalAlpha=1;ctx.setLineDash([]);}});}})}];}};
 series.attachPrimitive(primitive);
 function painted(b){return b.kind==='closed'?{time:b.time,open:b.open,high:b.high,low:b.low,close:b.close}:{time:b.time,open:b.open,high:b.high,low:b.low,close:b.close,color:'#e9c981',wickColor:'#e9c981',borderColor:'#e9c981'};}
 function detail(){const b=bars.find(b=>b.time===selected)||bars[bars.length-1];if(!b){legend.textContent='Waiting for confirmed candles…';legend.setAttribute('aria-label',legend.textContent);return;}const t=new Date(b.time*1000).toISOString().replace('T',' ').slice(0,16);legend.textContent=`${payload.frame} · ${t} UTC · ${b.kind==='closed'?'CLOSED':b.kind==='partial'?'PARTIAL — awaiting provider OHLC':b.kind==='pending'?'PENDING — awaiting history confirmation':'FORMING (unconfirmed)'}\nO ${b.open.toFixed(2)}  H ${b.high.toFixed(2)}  L ${b.low.toFixed(2)}  C ${b.close.toFixed(2)}`;legend.style.whiteSpace='pre-line';legend.setAttribute('aria-label',legend.textContent.replace(/\n/g,'; '));}
 function declutter(){queued=false;const h=plot.clientHeight,chosen=Core.labels(lines.map(it=>({id:it.id,y:series.priceToCoordinate(it.band.price),priority:it.band.priority})),payload.price>0?series.priceToCoordinate(payload.price):null,h,Math.max(20,18*(payload.font||1)));for(const it of lines)it.line.applyOptions({axisLabelVisible:chosen.has(it.id),title:chosen.has(it.id)?it.band.title:''});}
 function schedule(){if(!queued){queued=true;requestAnimationFrame(declutter);}}
 function overlays(){bands=(payload.bands||[]).filter(b=>b.macro?payload.contextOn:payload.zonesOn);const key=JSON.stringify(bands.map(b=>[b.id,b.low,b.high,b.price,b.title,b.watch,b.macro]));
  if(key!==bandKey){bandKey=key;for(const it of lines)series.removePriceLine(it.line);lines=bands.map(b=>({id:b.id,band:b,line:series.createPriceLine({price:b.price,color:color(b),lineVisible:false,axisLabelVisible:false,title:''})}));}
  else lines.forEach((it,i)=>it.band=bands[i]);
  const nearest=[true,false].map(side=>bands.filter(b=>b.support===side).sort((a,b)=>(a.macro-b.macro)||(a.watch-b.watch)||Core.distance(a,payload.price)-Core.distance(b,payload.price))[0]).filter(Boolean);
  const key2=JSON.stringify(nearest.map(b=>[b.id,b.low,b.high,b.title]));
  if(key2!==zoneInfoKey){
   zoneInfoKey=key2;const info=document.getElementById('zone-info');while(info.firstChild)info.removeChild(info.firstChild);
   for(const zone of nearest){const item=document.createElement('button');item.type='button';item.textContent=`${zone.title} ${zone.low.toFixed(2)}–${zone.high.toFixed(2)}`;item.addEventListener('click',()=>toggleZone(zone.id));info.appendChild(item);}
  }
  zoneDetail();
  if(primitive.request)primitive.request();schedule();}
 function toggleZone(id){activeZone=activeZone===id?null:id;zoneDetail();}
 function zoneDetail(){
  const panel=document.getElementById('zone-detail');const band=bands.find(z=>z.id===activeZone);
  panel.hidden=!band;
  if(!band){panel.textContent='';return;}
  const distance=Core.distance(band,payload.price);
  const evidence=band.pendingBreak?'Break pending · one adverse close':band.watch?'Unconfirmed · watch area':(band.tests||0)+' confirmed reactions'
    +(band.defendedVisits==null?'':' · '+band.defendedVisits+' defended / '+band.failedVisits+' failed visits');
  const text=`${band.title} ${band.low.toFixed(2)}–${band.high.toFixed(2)} · ${evidence} · ${distance===0?'Reference inside':'$'+distance.toFixed(2)+' from edge'}`
    +(band.reaction?' · last reaction '+band.reaction+' UTC':'')+(band.freshness?' · '+band.freshness:'');
  if(panel.textContent!==text)panel.textContent=text;
 }
 function render(p){
  restoring=true;try{
  const changedFrame=frame!==p.frameKey;if(changedFrame){closed=[];selected=null;selectedPrice=null;activeZone=null;zoneInfoKey='';emaKey='';chart.clearCrosshairPosition();}if(p.history)closed=Core.rows(p.history);
  const next=Core.combine(closed,p.forming,p.liveOn);const old=bars;const range=changedFrame?null:chart.timeScale().getVisibleLogicalRange();payload=p;frame=p.frameKey;
  if(!changedFrame&&Core.incremental(old,next)){for(let i=Math.max(0,old.length-1);i<next.length;i++)series.update(painted(next[i]));}
  else series.setData(next.map(painted));bars=next;
  if(changedFrame&&bars.length){chart.timeScale().setVisibleLogicalRange({from:Math.max(0,bars.length-(focusMode?45:100)),to:bars.length+6});chart.priceScale('right').applyOptions({autoScale:true});}
  else{const restored=Core.range(old,next,range);if(restored)chart.timeScale().setVisibleLogicalRange(restored);}
  const key=String(p.emaOn)+p.frameKey;if(p.history||key!==emaKey){emaKey=key;ema50.setData(p.emaOn?Core.ema(closed,50):[]);ema200.setData(p.emaOn?Core.ema(closed,200):[]);}
  const staleness=p.historyStatus?' · '+p.historyStatus:p.historyFresh?'':' · historical/time unverified';status.textContent=`${p.state} · ${p.referenceLabel||'MID'}${focusMode?' · Focus price':''} · ${p.count} closed bars${p.limited?' · limited history':''}${staleness}${p.emaOn&&closed.length<200?' · EMA200 needs 200 closed bars':''}`;
  if(lastFont!==p.font){lastFont=p.font;chart.applyOptions({layout:{fontSize:Math.min(18,Math.round(11*(p.font||1)))}});}legend.style.fontSize=Math.min(20,12*(p.font||1))+'px';for(const [node,size] of [[status,11],[document.getElementById('zone-info'),11],[document.querySelector('footer'),10]])node.style.fontSize=(size*Math.min(1.2,p.font||1))+'px';
  priceLine.applyOptions({price:p.price>0?p.price:0,lineVisible:p.price>0,axisLabelVisible:p.price>0,title:p.referenceLabel||'MID'});overlays();document.getElementById('empty').hidden=bars.length>0;
  if(selected&&!bars.some(b=>b.time===selected))selected=null;
  if(selected&&p.history){const b=bars.find(b=>b.time===selected);if(b){chart.setCrosshairPosition(selectedPrice==null?b.close:selectedPrice,b.time,series);}}
  detail();root.__aurumReady=true;
  }finally{restoring=false;}
 }
 chart.subscribeCrosshairMove(p=>{if(restoring)return;selected=p.time!=null&&bars.some(b=>b.time===p.time)?p.time:null;selectedPrice=selected&&p.point?series.coordinateToPrice(p.point.y):null;detail();});chart.timeScale().subscribeVisibleLogicalRangeChange(schedule);
 chart.subscribeClick(p=>{
  if(!p.point)return;
  const price=series.coordinateToPrice(p.point.y),other=series.coordinateToPrice(p.point.y+5);
  if(price==null||other==null)return;
  const band=Core.pickBand(bands,price,Math.abs(price-other));
  if(band)toggleZone(band.id);else if(activeZone){activeZone=null;zoneDetail();}
 });
 function resetView(focus){
  focusMode=focus;selected=null;selectedPrice=null;activeZone=null;chart.clearCrosshairPosition();
  if(payload.frame)render(payload);else detail();
  if(bars.length)chart.timeScale().setVisibleLogicalRange({from:Math.max(0,bars.length-(focus?45:100)),to:bars.length+6});
  chart.priceScale('right').applyOptions({autoScale:true});
 }
 root.AurumChart={render,latest:()=>resetView(false),focusPrice:()=>resetView(true),inspect(){return {bars:bars.map(b=>({...b})),closedCount:closed.length,selected,selectedPrice,activeZone,focusMode,frame,range:chart.timeScale().getVisibleLogicalRange(),bands:bands.map(b=>({low:b.low,high:b.high,id:b.id})),labelCount:lines.filter(it=>it.line.options().axisLabelVisible).length,ema50:ema50.data().length,ema200:ema200.data().length};}};
})(typeof window!=='undefined'?window:globalThis);
