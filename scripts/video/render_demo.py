#!/usr/bin/env python3
"""Compose real Android UI captures into captioned product-demo films."""
import argparse, json, math, subprocess
from pathlib import Path
from functools import lru_cache
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import imageio_ffmpeg

DURATION=42
FPS=24
SCENES=[
 (0,5,'A FIRST LOOK','Good conversations.\nBetter notes.','Live Meeting Notes for Android.','idle'),
 (5,13,'01 / CAPTURE','Stay with the\nconversation.','Follow speech as text.','live'),
 (13,21,'02 / REVISIT','Return to the\nmoment.','Replay saved audio. Follow the words.','playback'),
 (21,29,'03 / SUMMARIZE','Keep the next\nsteps in view.','Optional AI summaries and action items.','summary'),
 (29,36,'04 / SHARE','Your notes.\nReady to go.','Copy text. Share it. Save a .txt file.','export'),
 (36,42,'BUILT FOR ANDROID','Listen. Capture.\nRemember.','Live Meeting Notes · by Oh-my-pi','summary'),
]
CREAM='#F6F5F0';GREEN='#203632';TEAL='#17695B';MUTED='#60716B';MINT='#D3EBD9'
FONTS=Path('/usr/share/fonts/truetype/dejavu')
@lru_cache(None)
def font(size,bold=False):
 return ImageFont.truetype(str(FONTS/('DejaVuSans-Bold.ttf' if bold else 'DejaVuSans.ttf')),size)

def ease(x):
 x=max(0,min(1,x));return 1-(1-x)**3

def text(draw,xy,words,size=30,fill=GREEN,bold=False,spacing=8):
 draw.multiline_text(xy,words,font=font(size,bold),fill=fill,spacing=spacing,stroke_width=0)

def background(w,h):
 im=Image.new('RGB',(w,h),CREAM);d=ImageDraw.Draw(im)
 # Quiet architectural rings; all branding is original vector geometry.
 if w>h:
  for r in [470,610,750]:d.ellipse((1550-r,530-r,1550+r,530+r),outline='#E0E7DC',width=2)
 else:
  for r in [340,440,540]:d.ellipse((540-r,840-r,540+r,840+r),outline='#E0E7DC',width=2)
 return im

def icon(im,x,y,size):
 d=ImageDraw.Draw(im);d.rounded_rectangle((x,y,x+size,y+size),radius=int(size*.25),fill=GREEN)
 for i,amp in enumerate([.20,.48,.70,.43,.20]):
  xx=x+size*(.25+i*.125);yy=y+size/2
  d.line((xx,yy-size*amp/2,xx,yy+size*amp/2),fill=MINT,width=max(2,int(size*.052)))

class Renderer:
 def __init__(self,assets,format):
  self.assets=assets;self.format=format
  self.w,self.h=(1920,1080) if format=='landscape' else (1080,1350)
  self.bg=background(self.w,self.h)
  # timeline.json lists real capture files grouped into scenes. Progression is
  # paced for the edit; no UI text, result, or button is fabricated by this script.
  self.timeline=json.loads((assets/'timeline.json').read_text())
  self.timeline.setdefault('idle',self.timeline.get('recorder'))
  self.cache={}
 def screenshot(self,scene,progress):
  files=self.timeline.get(scene,self.timeline.get('idle'))
  if isinstance(files,str):files=[files]
  idx=min(len(files)-1,int(max(0,progress)*len(files)))
  key=files[idx]
  if key not in self.cache:
   p=self.assets/key
   im=Image.open(p).convert('RGB')
   maxh=900
   maxw=620 if self.format=='landscape' else 600
   im.thumbnail((maxw,maxh),Image.Resampling.LANCZOS)
   self.cache[key]=im
  return self.cache[key]
 def scene_frame(self,index,t):
  start,end,label,headline,subtitle,asset=SCENES[index]
  progress=(t-start)/(end-start)
  im=self.bg.copy();d=ImageDraw.Draw(im);land=self.format=='landscape'
  if land:
   icon(im,96,68,56);text(d,(172,76),'LIVE MEETING NOTES',24,bold=True)
   text(d,(100,294),label,23,TEAL,True)
   y=352+int(15*(1-ease((t-start)/.7)))
   text(d,(92,y),headline,78,bold=True,spacing=16)
   d.line((100,570,200,570),fill=TEAL,width=5)
   text(d,(100,613),subtitle,30,MUTED)
   if index==3:
    text(d,(100,706),'Your own AI provider key is required.\nProvider charges may apply.',24,MUTED,spacing=9)
   if index==5:
    d.rounded_rectangle((100,718,426,770),26,fill=MINT)
    text(d,(122,731),'PRODUCT PREVIEW',20,TEAL,True)
  else:
   icon(im,54,38,42);text(d,(112,43),'LIVE MEETING NOTES',22,bold=True)
   text(d,(57,104),label,18,TEAL,True)
   # Headlines are arranged as one compact two-line block in social format.
   text(d,(53,142),headline,51,bold=True,spacing=3)
   text(d,(57,270),subtitle,23,MUTED)
  screen=self.screenshot(asset,progress)
  sw,sh=screen.size
  x=(1450-sw//2) if land else (self.w-sw)//2
  y=(self.h-sh)//2-5 if land else 324+(900-sh)//2
  # A simple app canvas, not a fake device or regenerated interface.
  shadow=Image.new('RGBA',im.size);sd=ImageDraw.Draw(shadow)
  sd.rounded_rectangle((x-12,y-8,x+sw+12,y+sh+20),28,fill=(18,49,41,46))
  shadow=shadow.filter(ImageFilter.GaussianBlur(18));im=Image.alpha_composite(im.convert('RGBA'),shadow).convert('RGB')
  d=ImageDraw.Draw(im);d.rounded_rectangle((x-7,y-7,x+sw+7,y+sh+7),23,fill=GREEN)
  mask=Image.new('L',screen.size);ImageDraw.Draw(mask).rounded_rectangle((0,0,sw-1,sh-1),17,fill=255)
  im.paste(screen,(x,y),mask)
  d=ImageDraw.Draw(im)
  if land:
   text(d,(100,933),'REAL APP UI  /  SAMPLE MEETING CONTENT',19,MUTED)
   basey=994
   for k in range(6):
    xx=100+k*104
    d.rounded_rectangle((xx,basey,xx+84,basey+4),2,fill=TEAL if k<=index else '#D7DED5')
   text(d,(1750,994),f'{index+1:02d} / 06',18,MUTED)
  else:
   # Persistent provenance in the footer; the summary screen also gets a
   # readable provider-key disclosure in a dedicated bar.
   d.rectangle((0,1244,self.w,self.h),fill=CREAM)
   footer=[('REAL APP UI · SAMPLE MEETING CONTENT',20,1250)]
   if index==3: footer += [('AI requires your own provider key.',23,1280),('Provider charges may apply.',23,1309)]
   for foot,size,yy in footer:
    f=font(size);length=d.textlength(foot,font=f)
    d.text(((self.w-length)/2,yy),foot,font=f,fill=MUTED)
  return im
 def frame(self,t):
  index=next((i for i,s in enumerate(SCENES) if s[0]<=t<s[1]),5)
  im=self.scene_frame(index,t)
  elapsed=t-SCENES[index][0]
  if index>0 and elapsed<.35:
   prev=self.scene_frame(index-1,SCENES[index][0]-.001)
   im=Image.blend(prev,im,ease(elapsed/.35))
  if t<.35:im=Image.blend(self.bg,im,ease(t/.35))
  if t>DURATION-.6:im=Image.blend(im,self.bg,ease((t-DURATION+.6)/.6))
  return im

def render(assets,out,format,soundtrack,preview_only=False):
 r=Renderer(assets,format);out.mkdir(parents=True,exist_ok=True)
 times=[2.5,9,17,25,32,38]
 for i,t in enumerate(times):r.frame(t).save(out/f'{format}-scene-{i+1}.jpg',quality=92)
 if preview_only:return
 ff=imageio_ffmpeg.get_ffmpeg_exe()
 target=out/f'Live-Meeting-Notes-{format}.mp4'
 cmd=[ff,'-y','-loglevel','error','-f','rawvideo','-vcodec','rawvideo','-pix_fmt','rgb24','-s',f'{r.w}x{r.h}','-r',str(FPS),'-i','-', '-i',str(soundtrack),'-map','0:v:0','-map','1:a:0','-c:v','libx264','-preset','fast','-crf','19','-pix_fmt','yuv420p','-c:a','aac','-b:a','192k','-t',str(DURATION),'-movflags','+faststart',str(target)]
 p=subprocess.Popen(cmd,stdin=subprocess.PIPE)
 try:
  for frame in range(DURATION*FPS):
   p.stdin.write(r.frame(frame/FPS).tobytes())
   if frame%(FPS*7)==0: print(f'{format}: {frame//FPS}/{DURATION}s',flush=True)
 finally:p.stdin.close()
 if p.wait()!=0:raise RuntimeError('Video encoder failed')
 print(target,flush=True)

def main():
 p=argparse.ArgumentParser();p.add_argument('--assets',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--soundtrack',type=Path);p.add_argument('--format',choices=['landscape','linkedin','both'],default='both');p.add_argument('--preview-only',action='store_true');a=p.parse_args()
 for fmt in (['landscape','linkedin'] if a.format=='both' else [a.format]):render(a.assets,a.output,fmt,a.soundtrack,a.preview_only)
if __name__=='__main__':main()
