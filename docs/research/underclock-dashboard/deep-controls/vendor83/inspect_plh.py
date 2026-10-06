"""Read-only stock AArch64 PLH module ABI inspection, Capstone 5.0.6.
Usage: python inspect_plh.py MODULE_DIR OUTDIR
"""
import argparse,struct,json,hashlib
from pathlib import Path
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN
p=argparse.ArgumentParser();p.add_argument('module_dir',type=Path);p.add_argument('outdir',type=Path);args=p.parse_args()
def elf(path):
 b=path.read_bytes();shoff=struct.unpack_from('<Q',b,40)[0];sz,n,si=struct.unpack_from('<HHH',b,58)
 secs=[struct.unpack_from('<IIQQQQIIQQ',b,shoff+sz*i) for i in range(n)];names=b[secs[si][4]:secs[si][4]+secs[si][5]]
 def section(i):return b[secs[i][4]:secs[i][4]+secs[i][5]]
 named={names[s[0]:].split(b'\0')[0].decode():i for i,s in enumerate(secs)};syms={}
 for i,s in enumerate(secs):
  if s[1]!=2:continue
  st=section(s[6]);raw=section(i)
  for j in range(0,len(raw),24):
   sn,info,other,idx,val,size=struct.unpack_from('<IBBHQQ',raw,j);nm=st[sn:].split(b'\0')[0].decode();syms[nm]={'section':idx,'value':val,'size':size,'type':info&15}
 return b,secs,section,syms
md=Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN);result=[];lines=[]
for name,obj in [('plh_vendor.ko','scmi_plh_vendor'),('plh_scmi.ko','scmi_id_table')]:
 path=args.module_dir/name;b,secs,section,syms=elf(path);symbol=syms[obj];raw=section(symbol['section'])[symbol['value']:symbol['value']+symbol['size']]
 # struct scmi_protocol.id is u8 at +0; scmi_device_id.protocol_id is u8 at +0.
 result.append({'module':name,'sha256':hashlib.sha256(b).hexdigest(),'symbol':obj,'symbol_section':symbol['section'],'symbol_offset':hex(symbol['value']),'protocol_id':hex(raw[0]),'initial_16_object_bytes':raw[:16].hex(' ')})
 if name=='plh_vendor.ko':
  for fn in ['scmi_plh_init_ipc_freq_tbl','scmi_plh_start_cmd','scmi_plh_stop_cmd','scmi_plh_set_sample_ms','scmi_plh_set_log_level','scmi_plh_set_u16_val']:
   f=syms[fn];data=section(f['section'])[f['value']:f['value']+f['size']];lines.append('['+fn+']')
   lines.extend(f'{a:08x} {m} {o}' for a,z,m,o in md.disasm_lite(data,f['value']));lines.append('')
args.outdir.mkdir(exist_ok=True,parents=True);(args.outdir/'stock-plh-analysis.json').write_text(json.dumps(result,indent=2)+'\n');(args.outdir/'stock-plh-excerpts.txt').write_text('\n'.join(lines));print(json.dumps(result,indent=2))
