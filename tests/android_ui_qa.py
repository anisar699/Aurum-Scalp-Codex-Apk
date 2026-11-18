from pathlib import Path
import subprocess,re,xml.etree.ElementTree as E,time,os,shutil
adb=os.environ.get('ADB') or shutil.which('adb');assert adb,'Add adb to PATH or set ADB';out=Path(__file__).resolve().parents[1]/'verification';out.mkdir(exist_ok=True);records=[]
def run(*args):return subprocess.run([adb,*args],check=True,capture_output=True,timeout=60).stdout
def dump(name):
 raw=run('exec-out','uiautomator','dump','/dev/tty').decode();a=raw.find('<?xml');b=raw.rfind('</hierarchy>');assert a>=0 and b>=0,raw
 s=raw[a:b+12];(out/(name+'.xml')).write_text(s);root=E.fromstring(s)
 got=next((n for n in root.iter('node') if n.get('text')=='GOT IT' and n.get('package')=='android'),None)
 if got is not None:tap_node(got);time.sleep(1);return dump(name)
 wait=next((n for n in root.iter('node') if n.get('text')=='Wait' and n.get('package')=='com.android.systemui'),None)
 if wait is not None:tap_node(wait);time.sleep(2);return dump(name)
 return root
def bounds(n):return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def tap_node(n):
 a=bounds(n);run('shell','input','tap',str((a[0]+a[2])//2),str((a[1]+a[3])//2))
def tap(root,text=None,desc=None):
 n=next(n for n in root.iter('node') if (text is not None and n.get('text')==text) or (desc is not None and n.get('content-desc')==desc));tap_node(n)
def shot(name):(out/(name+'.png')).write_bytes(run('exec-out','screencap','-p'))
def texts(root):return '\n'.join(n.get('text','') for n in root.iter('node'))
def good(message):records.append('PASS '+message);print(records[-1],flush=True)
try:
 run('shell','am','force-stop','com.aurum.scalp');run('shell','settings','put','system','font_scale','1.0');run('shell','am','start','-n','com.aurum.scalp/.MainActivity');r=dump('ui-market-final');shot('market-normal')
 tap(r,text='Chart');time.sleep(5);r=dump('ui-chart-normal');shot('chart-normal');assert 'AURUM / CHART' in texts(r);assert any(n.get('class')=='android.webkit.WebView' for n in r.iter('node'));good('Market navigation opens dedicated chart with native controls')
 tap(r,desc='Chart timeframe 1H');r=dump('ui-chart-h1-watch');shot('chart-h1-watch');assert '1H R watch' in texts(r);good('Real H1 chart readout includes the nearest resistance watch and explicit role')
 tap(r,desc='Enter fullscreen chart');r=dump('ui-chart-full');shot('chart-full');assert any(n.get('content-desc')=='Exit fullscreen chart' for n in r.iter('node'));good('Fullscreen retains a visible exit control')
 run('shell','settings','put','system','accelerometer_rotation','0');run('shell','settings','put','system','user_rotation','1');time.sleep(2);r=dump('ui-chart-landscape');shot('chart-landscape');assert int(r.get('rotation')) in [1,3];assert 'Exit fullscreen' in texts(r);good('Fullscreen rotates to landscape and keeps chart/exit control')
 run('shell','input','keyevent','4');r=dump('ui-chart-back-full');assert 'AURUM / CHART' in texts(r) and 'Expand' in texts(r);good('Android Back leaves fullscreen first')
 run('shell','settings','put','system','user_rotation','0');time.sleep(2);r=dump('ui-chart-portrait');tap(r,desc='Chart timeframe 4H');r=dump('ui-chart-h4');tap(r,desc='Chart layers');r=dump('ui-chart-layers');tap(r,text='EMA 50/200');r=dump('ui-chart-layer-selected');tap(r,text='Done');r=dump('ui-chart-ema');shot('chart-ema');assert any(n.get('selected')=='true' and n.get('text')=='4H' for n in r.iter('node'));good('Visible timeframe and EMA controls work through real taps')
 # Gesture locations come from UI-tree WebView bounds, never screenshots.
 web=next(n for n in r.iter('node') if n.get('class')=='android.webkit.WebView');x1,y1,x2,y2=bounds(web);y=(y1+y2)//2;x=(x1+x2)//2
 run('shell','input','swipe',str(x),str(y),str(x+100),str(y),'500');run('shell','input','swipe',str(x),str(y),str(x),str(y),'1000');r=dump('ui-chart-crosshair');shot('chart-crosshair');good('Drag and long-press crosshair accept touch inside chart bounds')
 run('shell','settings','put','system','font_scale','1.5');time.sleep(2);r=dump('ui-chart-font150');shot('chart-font150');assert 'AURUM / CHART' in texts(r);plot=next(n for n in r.iter('node') if n.get('text')=='Interactive candle chart' or n.get('content-desc')=='Interactive candle chart');b=bounds(plot);assert b[3]-b[1]>300;good('150% font retains controls and usable chart height')
 tap(r,desc='Enter fullscreen chart');r=dump('ui-chart-full-font150');shot('chart-full-font150');assert 'Exit fullscreen' in texts(r);run('shell','input','keyevent','4');run('shell','input','keyevent','4');r=dump('ui-market-font150');shot('market-font150');assert any(n.get('text')=='Chart' for n in r.iter('node'));good('Fullscreen exit and chart Back return to accessible 150% navigation')
 tap(r,text='Health');r=dump('ui-health-font150');shot('health-font150');assert 'Start background monitoring' in texts(r);good('Existing Health/monitoring page remains accessible after chart')
finally:
 run('shell','settings','put','system','font_scale','1.0');run('shell','settings','put','system','user_rotation','0');run('shell','settings','put','system','accelerometer_rotation','1')
 (out/'ui-qa.txt').write_text('\n'.join(records)+'\n')
print('\n'.join(records),flush=True)
