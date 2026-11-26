from pathlib import Path
import json, math, time, os, shutil
from playwright.sync_api import sync_playwright
r=Path(__file__).resolve().parents[1]; assets=r/'app/src/main/assets/chart'; out=r/'verification'; out.mkdir(exist_ok=True); results=[]
def check(ok,msg):
 assert ok,msg
 results.append('PASS '+msg)
with sync_playwright() as p:
 chromium=os.environ.get('CHROMIUM_PATH') or shutil.which('chromium') or shutil.which('chromium-browser')
 browser=p.chromium.launch(**({'executable_path':chromium} if chromium else {}),headless=True,args=['--no-sandbox'])
 ctx=browser.new_context(viewport={'width':720,'height':1000},device_scale_factor=1,has_touch=True,is_mobile=True)
 page=ctx.new_page(); errors=[]; requests=[]
 page.on('pageerror',lambda e:errors.append(str(e)))
 page.on('request',lambda req:requests.append(req.url))
 def route(rt):
  path=rt.request.url.split('appassets.androidplatform.net/',1)[-1]
  file=assets/path
  if path in ['index.html','chart.js','vendor/lightweight-charts.standalone.production.js']:
   rt.fulfill(path=str(file),content_type='text/html' if path.endswith('html') else 'text/javascript')
  else:rt.fulfill(status=403,body='blocked')
 page.route('**/*',route)
 page.goto('https://appassets.androidplatform.net/index.html')
 start=1760000400; count=260; dur=300
 rows=[]
 for n in range(count):
  o=4000+math.sin(n/7)*7;c=o+math.sin(n)*1.3
  rows.append([start+n*dur,o,max(o,c)+2,min(o,c)-2,c,'closed'])
 t=start+count*dur
 data=dict(frame='5m',frameKey='M5',now=t+15,price=4002,state='LIVE',history=rows,count=count,limited=False,historyFresh=True,zonesOn=True,contextOn=True,emaOn=True,liveOn=True,nearOn=True,font=1,atr=4,forming=[[t,4001,4004,3999,4002,'forming']],bands=[dict(id='s',low=3994,high=3995,price=3994.5,support=True,watch=False,macro=False,title='5m S',priority=20),dict(id='r',low=4008,high=4009,price=4008.5,support=False,watch=False,macro=False,title='5m R',priority=25),dict(id='h1',low=4010,high=4011,price=4010.5,support=False,watch=False,macro=True,title='1H R',priority=22)])
 def render(d):page.evaluate('(p)=>window.AurumChart.render(p)',d);page.wait_for_timeout(180)
 def inspect():return page.evaluate('window.AurumChart.inspect()')
 render(data)
 z=inspect();check(z['closedCount']==260 and len(z['bars'])==261 and z['ema50']==211 and z['ema200']==61,'Real library draws closed + forming candles and EMA50/200')
 check(len(z['bands'])==3 and z['bands'][0]['low']==3994 and z['bands'][0]['high']==3995,'Native zone bounds survive rendering unchanged')
 check(0<z['labelCount']<=6,'Axis labels are bounded and visible')
 plot=page.locator('#plot').bounding_box();x=plot['x']+plot['width']*.55;y=plot['y']+plot['height']*.45
 page.mouse.move(x,y);page.wait_for_timeout(150);s=inspect()['selected'];check(s is not None and 'UTC' in page.locator('#legend').inner_text(),'Crosshair selects a real candle and exact UTC OHLC')
 original_range=inspect()['range'];page.mouse.move(x,y);page.mouse.wheel(0,-300);page.wait_for_timeout(200);zoom=inspect()['range'];check(abs((zoom['to']-zoom['from'])-(original_range['to']-original_range['from']))>1,'Wheel zoom changes visible time range')
 page.mouse.move(x,y);page.mouse.down();page.mouse.move(x+160,y,steps=12);page.mouse.up();page.wait_for_timeout(200);pan=inspect()['range'];check(abs(pan['from']-zoom['from'])>2,'Drag pans chart history')
 # Correct an earlier candle, keep timestamp selection and the panned range.
 page.mouse.move(x-50,y);page.wait_for_timeout(150);selected=inspect()['selected'];check(selected is not None,'Panned chart still supports exact candle selection');corrected=json.loads(json.dumps(data));idx=next((i for i,b in enumerate(rows) if b[0]==selected),70);corrected['history'][idx][2]+=25;corrected['forming'][0][2]=4010;corrected['forming'][0][4]=4003
 before=inspect()['range'];picked=inspect()['selectedPrice'];render(corrected);z=inspect();check(z['bars'][idx]['high']==corrected['history'][idx][2],'Historical correction replaces the actual chart data')
 check(abs(z['selectedPrice']-picked)<.001,'History refresh preserves the exact crosshair price');check(z['selected']==selected and ('H '+format(corrected['history'][idx][2],'.2f')) in page.locator('#legend').inner_text(),'Selected candle legend refreshes to corrected OHLC');check(abs(z['range']['from']-before['from'])<.1 and abs(z['range']['to']-before['to'])<.1,'History refresh preserves user pan and zoom')
 # Live-only update changes close without erasing high/low.
 tick={k:v for k,v in corrected.items() if k!='history'};tick['forming']=[[t,4001,4010,3999,4000,'forming']];render(tick);z=inspect();check(z['bars'][-1]['high']==4010 and z['bars'][-1]['low']==3999 and z['bars'][-1]['close']==4000,'Live tick preserves observed high/low after reversal')
 tick['now']=t+dur+2;tick['forming']=[[t,4001,4010,3999,4000,'pending'],[t+dur,4003,4004,4002,4004,'partial']];render(tick);z=inspect();check(z['bars'][-2]['time']==t and z['bars'][-2]['close']==4000 and z['bars'][-1]['time']==t+dur,'Rollover creates a separate candle and retains pending confirmation')
 tick['zonesOn']=False;tick['contextOn']=False;tick['emaOn']=False;tick['liveOn']=False;render(tick);z=inspect();check(len(z['bands'])==0 and z['ema50']==z['ema200']==0 and len(z['bars'])==260,'Layer toggles remove zones, context, EMA and forming preview')
 short=dict(data,frame='1H',frameKey='H1',history=rows[:168],count=168,limited=True,forming=[]);render(short);z=inspect();check(z['ema200']==0 and z['ema50']==119 and 'EMA200 needs 200' in page.locator('#status').inner_text(),'Short H1 history hides EMA200 with an honest explanation')
 # Nearest watch must be readable even when stronger distant zones exist.
 watch=dict(data,history=rows,forming=[],bands=[dict(id='near-watch',low=4002.2,high=4002.5,price=4002.35,support=False,watch=True,retestWatch=True,macro=False,title='5m R watch',priority=1090),dict(id='far-r',low=4008,high=4009,price=4008.5,support=False,watch=False,macro=False,title='5m R',priority=1010)])
 render(watch)
 labels=page.locator('#zone-info button')
 check(labels.count()==2 and '4002.20' in labels.first.inner_text() and '4008.00' in labels.nth(1).inner_text(),'Nearest retest watch appears first alongside the confirmed resistance')
 labels.first.click()
 check('Retest watch · new role unconfirmed' in page.locator('#zone-detail').inner_text(),'Retest watch explains the pending role')
 labels.nth(1).click()
 check('past swing reactions' in page.locator('#zone-detail').inner_text(),'Confirmed band detail describes its reaction history')
 check(' '.join(page.locator('#legend').get_attribute('aria-label').split())==' '.join(page.locator('#legend').inner_text().replace('\n','; ').split()),'Accessible candle text contains current OHLC values')
 confirmed=dict(data['bands'][0],tests=3,defendedVisits=2,failedVisits=1)
 pressured=dict(data['bands'][1],watch=True,pendingBreak=True,title='5m R pending')
 render(dict(data,bands=[confirmed,pressured]))
 labels.first.click()
 check('Break pending · one adverse close' in page.locator('#zone-detail').inner_text(),'Chart separates a first adverse close from confirmed zones')
 labels.nth(1).click()
 check('2 defended / 1 failed visits' in page.locator('#zone-detail').inner_text(),'Chart detail reports past visits')
 # Actual two-pointer touchscreen pinch; coordinates derived from plot DOM bounds.
 render(data);client=ctx.new_cdp_session(page);b=page.locator('#plot').bounding_box();cx=b['x']+b['width']*.48;cy=b['y']+b['height']*.5;before=inspect()['range']
 def touch(kind,gap):client.send('Input.dispatchTouchEvent',{'type':kind,'touchPoints':[] if kind=='touchEnd' else [{'x':cx-gap,'y':cy,'id':1},{'x':cx+gap,'y':cy,'id':2}]})
 touch('touchStart',45)
 for gap in range(50,121,10):touch('touchMove',gap);page.wait_for_timeout(35)
 touch('touchEnd',0);page.wait_for_timeout(250);after=inspect()['range'];check(abs((after['to']-after['from'])-(before['to']-before['from']))>2,'Two-finger pinch changes chart zoom')
 page.evaluate('window.AurumChart.focusPrice()');page.wait_for_timeout(160); focused=inspect();check(focused['focusMode'] and abs(focused['range']['to']-focused['range']['from']-51)<1,'Focus price opens recent candles')
 page.evaluate('window.AurumChart.latest()');page.wait_for_timeout(150);z=inspect();check(z['range']['to']>len(z['bars'])-1 and abs(z['range']['to']-z['range']['from']-106)<1,'Latest/reset restores current candles and default scale')
 page.screenshot(path=str(out/'browser-chart.png'))
 page.set_viewport_size({'width':360,'height':432});render(dict(short,font=1.5,state='MARKET CLOSED',historyFresh=False,bands=[confirmed,pressured,watch['bands'][1]]));check(page.locator('#plot').bounding_box()['height']>=150,'150% font keeps usable plot height on a narrow Android-sized stage');page.screenshot(path=str(out/'browser-font150.png'))
 render(dict(data,history=[],forming=[],count=0,state='UNAVAILABLE',historyFresh=False));check(not page.locator('#empty').is_hidden(),'Empty/offline history has explicit waiting state')
 render(dict(data,forming=[]))
 late=inspect()
 check(late['range'] is not None and abs(late['range']['to']-late['range']['from']-106)<1,'Late first history opens the most recent 100 candles')
 # A tick can create a one-bar preview before the history request completes.
 render(dict(data,frame='4H',frameKey='H4',history=[],count=0))
 render(dict(data,frame='4H',frameKey='H4'))
 late=inspect()
 check(late['range'] is not None and abs(late['range']['to']-late['range']['from']-106)<1,'First closed history restores the recent view after a forming preview')
 render(dict(data,forming=[],sessionLevels=[dict(label='PDH',price=4004,**{'from':t-6*dur+1}),
    dict(label='Future range',price=4001,**{'from':t+dur})],sessionStatus='Sessions · 1/5 ranges ready',
    sweeps=[dict(time=t-dur,level='Asia L',high=False),dict(time=t,level='Asia H',high=True)]))
 layers=inspect()
 check(layers['sessionStarts']==[t-5*dur,None] and 'Sessions · 1/5' in page.locator('#status').inner_text(),
       'Session levels anchor at the first bar after availability and omit future drawing')
 check(layers['sweeps']==1 and 'Sweep & reclaim · Asia L' in page.locator('#legend').inner_text(),
       'Only closed candle sweeps become markers and the candle legend names the reference')
 render(dict(data,forming=[]))
 check(inspect()['sessionStarts']==[] and inspect()['sweeps']==0,'Toggling session and sweep layers off clears their overlays')
 check(not errors,'No JavaScript or renderer errors')
 check(all(u.startswith('https://appassets.androidplatform.net/') for u in requests),'Renderer makes no external feed/network requests')
 browser.close()
(out/'browser-qa.txt').write_text('\n'.join(results)+'\n');print('\n'.join(results))
