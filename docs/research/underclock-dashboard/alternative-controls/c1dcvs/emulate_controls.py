"""Host-only bounded simulation of C1 request handler. No device access.
External platform-existence callee intercepted, not executed. Trace configurator
executes only against host memory.
"""
from pathlib import Path
import struct,json,hashlib,argparse
from unicorn import Uc,UC_ARCH_RISCV,UC_MODE_RISCV32,UC_HOOK_CODE,UC_HOOK_MEM_WRITE
from unicorn.riscv_const import *
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--image',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
image=args.image; b=image.read_bytes()
expected_sha256='19de4a8dd45eaa80ca2dd33d7a73661373805efd3970c994f2274e920b1d3808'
if hashlib.sha256(b).hexdigest()!=expected_sha256:raise SystemExit('Stock CPUCP SHA-256 mismatch; refusing simulation')
off=struct.unpack_from('<I',b,28)[0];sz,num=struct.unpack_from('<HH',b,42)
segs=[struct.unpack_from('<8I',b,off+i*sz) for i in range(num)]
cases=[('ipc_set',15,[1,123]),('ipc_get',16,[0,0]),('efreq_cluster0',17,[0,456]),('efreq_cluster1',17,[1,456]),('efreq_cluster3',17,[3,789]),('efreq_cluster4',17,[4,100]),('efreq_get',18,[0,0]),('hysteresis49',19,[49,0]),('hysteresis_get',20,[0,0]),('mode0',21,[0,0]),('mode1',21,[1,0]),('mode2',21,[2,0]),('mode3',21,[3,0]),('mode_get',22,[0,0]),('trace_enable',13,[1,0]),('trace_get',14,[0,0])]
results=[]
for name,msg,words in cases:
 uc=Uc(UC_ARCH_RISCV,UC_MODE_RISCV32);mapped=set()
 for typ,of,va,pa,fs,ms,flags,al in segs:
  if typ!=1:continue
  for page in range(va&~4095,(va+ms+4095)&~4095,4096):
   if page not in mapped:uc.mem_map(page,4096);mapped.add(page)
  if fs:uc.mem_write(va,b[of:of+fs])
 for va in [0x10000000,0x10010000,0x10020000]:uc.mem_map(va,4096)
 uc.mem_write(0x10020000,b'\x13\x00\x00\x00')
 uc.mem_write(0x17d28050,struct.pack('<I',0x17d2a710))
 uc.mem_write(0x17d28148,struct.pack('<8I',0,1400000,1500000,1600000,6,3,0,0))
 uc.mem_write(0x10000000,struct.pack('<8I',*words,*[0]*6))
 uc.reg_write(UC_RISCV_REG_A1,msg);uc.reg_write(UC_RISCV_REG_A2,0x10000000);uc.reg_write(UC_RISCV_REG_SP,0x10011000);uc.reg_write(UC_RISCV_REG_RA,0x10020000)
 steps=[];writes=[];external=[]
 def code(uc,ad,size,ud):
  if not (0x17d201a8<=ad<0x17d2031c or 0x17d210d6<=ad<0x17d210f0 or ad in [0xd82e5e82,0x10020000]):raise RuntimeError('Unexpected PC '+hex(ad))
  steps.append(hex(ad))
  if ad==0x10020000:uc.emu_stop();return
  if ad==0xd82e5e82:
   external.append({'address':hex(ad),'a0':uc.reg_read(UC_RISCV_REG_A0),'a1':uc.reg_read(UC_RISCV_REG_A1),'substitute':'platform exists=1'})
   uc.reg_write(UC_RISCV_REG_A0,1);uc.reg_write(UC_RISCV_REG_PC,uc.reg_read(UC_RISCV_REG_RA))
 def write(uc,access,ad,size,val,ud):
  allowed=(0x10000000<=ad<0x10001000 or 0x10010000<=ad<0x10011000 or 0x17d28148<=ad<0x17d28180 or ad==0x17d2868c)
  if not allowed:raise RuntimeError('Unexpected host memory write '+hex(ad))
  writes.append({'address':hex(ad),'size':size,'value':val})
 uc.hook_add(UC_HOOK_CODE,code);uc.hook_add(UC_HOOK_MEM_WRITE,write)
 try:uc.emu_start(0x17d201a8,0x10020004,count=2000)
 except Exception as exc:
  print(name,steps[-10:],hex(uc.reg_read(UC_RISCV_REG_RA)),hex(uc.reg_read(UC_RISCV_REG_SP)),writes[-10:]);raise
 if not steps or steps[-1]!='0x10020000':raise RuntimeError('instruction limit or failed return')
 results.append({'case':name,'message':msg,'request_words':words,'return_bytes':uc.reg_read(UC_RISCV_REG_A0),'response_words':list(struct.unpack('<4I',uc.mem_read(0x10000000,16))),'fields':{'thresholds':list(struct.unpack('<3I',uc.mem_read(0x17d2814c,12))),'hysteresis_internal':struct.unpack('<I',uc.mem_read(0x17d28158,4))[0],'mode_full':struct.unpack('<I',uc.mem_read(0x17d28164,4))[0],'mode_flag':uc.mem_read(0x17d28168,1)[0],'trace':struct.unpack('<I',uc.mem_read(0x17d28160,4))[0]},'external_substitutions':external,'steps':steps,'writes':writes})
output={'image_sha256':hashlib.sha256(b).hexdigest(),'scope':'Host-only SCMI0x87 handler simulation; scratch state and platform existence callee substitution. No transport or MMIO executed.','cases':results}
args.output.write_text(json.dumps(output,indent=2)+'\n')
summary={'image_sha256':expected_sha256,'scope':output['scope'],'synthetic_initial_thresholds':[1400000,1500000,1600000],'cases':[{k:v for k,v in r.items() if k not in ['steps','writes']} for r in results]}
args.output.with_suffix('.summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print('Validated',len(results),'host scratch cases; no device access:',args.output)
