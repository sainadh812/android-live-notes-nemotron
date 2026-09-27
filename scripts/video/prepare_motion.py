#!/usr/bin/env python3
"""Use real screen-recorded motion for live transcript and playback scenes."""
import argparse,csv,json,subprocess
from pathlib import Path
import imageio_ffmpeg

def main():
 p=argparse.ArgumentParser();p.add_argument('--evidence',type=Path,required=True);a=p.parse_args()
 e=a.evidence;assets=e/'demo-capture';raw=e/'live-notes-demo-raw.mp4'
 origin=float((assets/'capture-origin-elapsed-realtime-ms.txt').read_text())/1000
 started=float((e/'recording-start-uptime.txt').read_text().split()[0])
 offset=origin-started
 with (assets/'timeline.tsv').open() as f:
  marks={row['frame']:float(row['elapsed_ms'])/1000 for row in csv.DictReader(f,delimiter='\t')}
 timeline=json.loads((assets/'timeline.json').read_text())
 clips={'live':(max(marks['recorder']+2.2,marks['live-1']-1),marks['live']+.15),
        'playback':(marks['playback']-.6,marks['seek']+.2)}
 ff=imageio_ffmpeg.get_ffmpeg_exe()
 for key,(start,end) in clips.items():
  folder=assets/('motion-'+key);folder.mkdir(exist_ok=True)
  for previous in folder.glob('frame-*.jpg'): previous.unlink()
  command=[ff,'-y','-loglevel','error','-ss',str(max(0,offset+start)),'-i',str(raw),'-t',str(end-start),'-vf','fps=12,scale=600:-2','-q:v','2',str(folder/'frame-%04d.jpg')]
  subprocess.run(command,check=True)
  frames=sorted(folder.glob('frame-*.jpg'))
  if len(frames)<10:raise RuntimeError('Too few recorded frames for '+key)
  timeline[key]=[str(f.relative_to(assets)) for f in frames]
  print(key,len(frames),'real video frames',flush=True)
 timeline['idle']=timeline['recorder']
 (assets/'timeline-stills.json').write_text((assets/'timeline.json').read_text())
 (assets/'timeline.json').write_text(json.dumps(timeline,indent=2))
 (assets/'motion-edit.json').write_text(json.dumps({'raw_origin_offset_seconds':offset,'clip_ranges_from_harness_origin':clips,'fps':12,'note':'Actual emulator motion, paced to fit caption scenes; sample content.'},indent=2))
if __name__=='__main__':main()
