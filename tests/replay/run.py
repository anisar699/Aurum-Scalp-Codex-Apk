from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json, os, shutil, subprocess, tempfile, urllib.request
from email.utils import parsedate_to_datetime
from pathlib import Path
import sys

here = Path(__file__).resolve().parent
root = here.parents[1]
frames = {'M1': ('1m', 1, 720), 'M5': ('5m', 5, 720), 'M15': ('15m', 15, 480), 'H1': ('1h', 60, 1000), 'H4': ('4h', 240, 360)}

def samples(frame):
    name, minutes, limit = frames[frame]
    fixture = root / f'app/src/test/resources/biquote/{name}.json'
    result = [('archive', json.loads(fixture.read_text()), None, frame)]
    if '--live' in sys.argv:
        try:
            url=f'https://biquote.io/api/XAUUSD/ohlc?interval={name}&limit={limit}'
            with urllib.request.urlopen(url, timeout=12) as reply:
                as_of = parsedate_to_datetime(reply.headers['Date']).timestamp()
                result.append(('provider-sample', json.load(reply), as_of, frame))
        except Exception as e:
            print(f'{frame} provider sample unavailable: {type(e).__name__}', flush=True)
    return result

with tempfile.TemporaryDirectory(prefix='aurum-replay-') as name:
    scratch = Path(name)
    files = []
    with ThreadPoolExecutor(max_workers=2) as pool:
        groups = list(pool.map(samples, frames))
    for group in groups:
        for label, payload, as_of, frame in group:
            interval, minutes, limit = frames[frame]
            rows = {}
            assert payload['symbol'] == 'XAUUSD' and payload['interval'] == interval
            for b in payload['bars']:
                if b.get('isOpen') is not False:
                    continue
                seconds = int(datetime.fromisoformat(b['openTime'].replace('Z', '+00:00')).timestamp())
                if seconds % (minutes*60) or as_of is not None and seconds + minutes*60 > as_of:
                    continue
                o,h,l,c = (float(b[k]) for k in ('open','high','low','close'))
                if 0<l<=min(o,c) and max(o,c)<=h:
                    rows[seconds]=[o,h,l,c]
            csv = scratch / f'{label}__{frame}.csv'
            csv.write_text(''.join(','.join(map(str,(t*1000,*values)))+'\n' for t,values in sorted(rows.items())))
            files.append(csv)
            print(f'{label} {frame} valid closed={len(rows)}' + (f' requested={limit}' if as_of else ''), flush=True)
    jdk = os.environ.get('JAVA_HOME')
    javac = str(Path(jdk)/'bin/javac') if jdk else shutil.which('javac')
    java = str(Path(jdk)/'bin/java') if jdk else shutil.which('java')
    native = root / 'app/src/main/java/com/aurum/scalp'
    subprocess.run([javac,'-d',str(scratch),str(here/'ZoneReplay.java'), *[str(native/(file+'.java')) for file in ('Market','MarketRegime','ZoneEngine','ZoneSelection','HistoryQuality','MarketClock','PriceFormat','ZoneVisits')]],check=True)
    subprocess.run([java,'-cp',str(scratch),'com.aurum.scalp.ZoneReplay',*map(str,files)],check=True)
