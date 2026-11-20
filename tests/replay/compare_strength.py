"""Compare Aurum rankings on an external M5 OHLC archive and optional Biquote snapshots."""
import argparse
import csv
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from zipfile import ZipFile

APP = Path(__file__).resolve().parents[2]
NATIVE = APP / 'app/src/main/java/com/aurum/scalp'
# Approximate full analyses at a warmed app's retained history sizes.
FRAMES = {'M5': ('5m', 5, 720, 36), 'M15': ('15m', 15, 480, 18),
          'H1': ('1h', 60, 1000, 12), 'H4': ('4h', 240, 360, 6)}
SPLIT = datetime(2024, 1, 1, tzinfo=timezone.utc).timestamp()
FILES = ['Market', 'ZoneEngine', 'ZoneVisits', 'MarketClock']


def market_archive(path):
    with ZipFile(path) as archive:
        csv_name = next(name for name in archive.namelist() if name.endswith('.csv'))
        with archive.open(csv_name) as source:
            grid = {}
            for bar in csv.DictReader(io.TextIOWrapper(source, encoding='utf-8-sig')):
                t = int(datetime.strptime(bar['date'], '%m/%d/%Y %H:%M').replace(tzinfo=timezone.utc).timestamp())
                o, h, low, c = (float(bar[key]) for key in ('open', 'high', 'low', 'close'))
                if t % 300 == 0 and 0 < low <= min(o, c) <= max(o, c) <= h:
                    grid[t] = (o, h, low, c)
    return sorted(grid.items())


def aggregate(source, minutes):
    duration = minutes * 60
    expected = minutes // 5
    buckets = {}
    for t, (o, h, low, c) in source:
        slot = buckets.setdefault(t // duration * duration, [o, h, low, c, 0])
        slot[1] = max(h, slot[1])
        slot[2] = min(low, slot[2])
        slot[3] = c
        slot[4] += 1
    # Keep open-market candles with at most one third missing components, allowing short session interruptions.
    return [(t, tuple(values[:4])) for t, values in buckets.items() if values[4] * 3 >= expected * 2]


def biquote(path, minutes):
    payload = json.loads(path.read_text())
    as_of = parsedate_to_datetime(payload['_asOf']).timestamp()
    result = {}
    for row in payload['bars']:
        t = int(datetime.fromisoformat(row['openTime'].replace('Z', '+00:00')).timestamp())
        if row.get('isOpen') is not False or t % (minutes * 60) or t + minutes * 60 > as_of:
            continue
        o, h, low, c = (float(row[key]) for key in ('open', 'high', 'low', 'close'))
        if 0 < low <= min(o, c) <= max(o, c) <= h:
            result[t] = (o, h, low, c)
    return sorted(result.items())


def run_slices(args, folder):
    source = market_archive(args.archive)
    print(f'Archive original validated M5 candles={len(source)}', flush=True)
    files = {}
    for frame, (_, minutes, _, _) in FRAMES.items():
        f = folder / (frame + '.csv')
        rows = source if minutes == 5 else aggregate(source, minutes)
        f.write_text(''.join(','.join(map(str, (t*1000, *bar))) + '\n' for t, bar in rows))
        files[frame] = f
    java_home = os.getenv('JAVA_HOME')
    javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    subprocess.run([javac, '-d', str(folder), str(Path(__file__).with_name('CompareStrength.java')),
                    *[str(NATIVE / (name + '.java')) for name in FILES]], check=True)
    start, stop = (0, SPLIT) if args.split == 'development' else (SPLIT, 2_000_000_000)
    for frame, (_, _, depth, stride) in FRAMES.items():
        print(args.split + ' archive ', end='', flush=True)
        subprocess.run([java, '-Xmx512m', '-cp', str(folder), 'com.aurum.scalp.CompareStrength', frame,
                        str(int(start * 1000)), str(int(stop * 1000)), str(stride), str(depth),
                        str(files[frame]), args.weights], check=True)
    if args.biquote:
        for frame, (name, minutes, depth, _) in FRAMES.items():
            f = folder / (frame + '-bq.csv')
            rows = biquote(args.biquote / (name + '.json'), minutes)
            f.write_text(''.join(','.join(map(str, (t*1000, *bar))) + '\n' for t, bar in rows))
            print('Biquote MID holdout ', end='', flush=True)
            subprocess.run([java, '-Xmx256m', '-cp', str(folder), 'com.aurum.scalp.CompareStrength', frame,
                            '0', '2000000000000', '6', str(depth), str(f), args.weights], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--split', choices=['development', 'holdout'], required=True)
    parser.add_argument('--biquote', type=Path)
    parser.add_argument('--weights', default='0,0.35,0.65,1.0')
    with tempfile.TemporaryDirectory(prefix='aurum-strength-') as temp:
        run_slices(parser.parse_args(), Path(temp))
