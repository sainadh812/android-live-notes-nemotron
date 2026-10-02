#!/usr/bin/env python3
"""Monitor a real benchmark process; retain exit, memory, and disk-failure evidence."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path,required=True)
    parser.add_argument('--min-free-bytes',type=int,default=1024**3)
    parser.add_argument('--timeout-seconds',type=int,default=5400)
    parser.add_argument('command',nargs=argparse.REMAINDER)
    args=parser.parse_args()
    command=args.command[1:] if args.command and args.command[0]=='--' else args.command
    if not command: parser.error('Supply a benchmark command after --')
    args.report.parent.mkdir(parents=True,exist_ok=True)
    start=time.monotonic()
    process=subprocess.Popen(command)
    record={'status':'running','pid':process.pid,'command':command,'peak_sampled_rss_kib':0,'min_free_disk_bytes':None,'samples':[]}
    def save():
        temporary=args.report.with_suffix('.tmp')
        temporary.write_text(json.dumps(record,indent=2)+'\n')
        temporary.replace(args.report)
    save()
    while process.poll() is None:
        elapsed=time.monotonic()-start
        free=shutil.disk_usage(args.report.parent).free
        record['min_free_disk_bytes']=min(record['min_free_disk_bytes'] or free,free)
        try:
            fields=Path(f'/proc/{process.pid}/status').read_text().splitlines()
            rss=int(next(line for line in fields if line.startswith('VmRSS:')).split()[1])
        except (OSError,StopIteration): rss=0
        record['peak_sampled_rss_kib']=max(record['peak_sampled_rss_kib'],rss)
        record['samples'].append({'elapsed_seconds':round(elapsed,1),'rss_kib':rss,'free_disk_bytes':free})
        if free < args.min_free_bytes or elapsed > args.timeout_seconds:
            record['stop_reason']='minimum_free_disk_reached' if free < args.min_free_bytes else 'time_limit_reached'
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill()
        save()
        time.sleep(2)
    record['exit_code']=process.returncode
    record['elapsed_seconds']=round(time.monotonic()-start,3)
    record['status']='completed' if process.returncode==0 else 'failed'
    if process.returncode == -signal.SIGKILL:
        record['diagnosis']='SIGKILL; inspect kernel logs to distinguish OOM from another external kill'
    save()
    return 0 if process.returncode==0 else 1

if __name__=='__main__':
    raise SystemExit(main())
