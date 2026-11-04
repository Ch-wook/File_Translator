#!/usr/bin/env python3
"""Independent HWPX package and page-raster preservation checks on synthetic signatures."""
from pathlib import Path
from PIL import Image,ImageChops,ImageStat
import io,json,zipfile,xml.etree.ElementTree as ET
root=Path('artifacts/signature-conversion')
results=json.loads((root/'signature-results.json').read_text())
assert len(results)==2 and {r['format'] for r in results}=={'hwp','hwpx'}
for r in results:
 assert r['signatureTouch'] and r['tableTextPreserved'] and r['tableStructurePreserved'] and r['savedAndReopened']
 colorSeen=False
 for i in range(r['pages']):
  before=Image.open(root/f"{r['format']}-before-{i}.png").convert('RGB')
  after=Image.open(root/f"{r['format']}-after-{i}.png").convert('RGB')
  assert before.size==after.size
  def blue_pixels(image):
   w,h=image.size
   return {(i%w,i//w) for i,(r,g,b) in enumerate(image.getdata()) if abs(r-20)<18 and abs(g-88)<18 and abs(b-189)<18}
  a=blue_pixels(before);b=blue_pixels(after)
  if a:
   colorSeen=True
   assert len(a)>10 and len(b)>10
   assert abs(len(a)-len(b))/max(len(a),len(b))<.08,('signature area changed',len(a),len(b))
   center=lambda points:(sum(x for x,y in points)/len(points),sum(y for x,y in points)/len(points))
   ac,bc=center(a),center(b)
   assert abs(ac[0]-bc[0])<2 and abs(ac[1]-bc[1])<2,('signature moved',ac,bc)
   near=sum(any((x+dx,y+dy) in b for dx in (-1,0,1) for dy in (-1,0,1)) for x,y in a)
   assert near/len(a)>.95,('signature shape changed',near/len(a))
  mean=sum(ImageStat.Stat(ImageChops.difference(before,after)).mean)/3
  assert mean<5,('saved page changed',r['format'],i,mean)
 assert colorSeen,('missing visible signature',r['format'])
with zipfile.ZipFile(root/'signature.hwpx') as z:
 assert z.testzip() is None
 sections=[ET.fromstring(z.read(n)) for n in z.namelist() if n.startswith('Contents/section') and n.endswith('.xml')]
 text=''.join(''.join(n.itertext()) for n in sections)
 assert 'SIGNATURE_CELL' in text and 'OTHER_CELL_PRESERVE' in text
 tables=[n for section in sections for n in section.iter() if n.tag.split('}')[-1]=='tbl']
 assert any(n.get('rowCnt')=='2' and n.get('colCnt')=='2' for n in tables)
 signature=False
 for name in z.namelist():
  if not name.startswith('BinData/') or not name.lower().endswith('.png'):continue
  im=Image.open(io.BytesIO(z.read(name))).convert('RGBA')
  if im.getextrema()[3][0]==0 and im.getextrema()[3][1]>0:
   pixels=list(im.getdata())
   if any(a>150 and b>r*1.5 for r,g,b,a in pixels) and any(a>150 and r<50 and g<50 and b<50 for r,g,b,a in pixels):signature=True
 assert signature,'Transparent black/blue signature PNG must be embedded'
print('INDEPENDENT_SIGNATURE_PACKAGE_AND_PAGE_CHECKS_PASSED')
