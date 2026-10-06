#!/usr/bin/env python3
"""Host-only bounded ELF decoding; no device/MMIO access. Requires capstone5.0.6."""
from pathlib import Path
import argparse,struct,hashlib,json
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN,CS_ARCH_RISCV,CS_MODE_RISCV32,CS_MODE_RISCVC
p=argparse.ArgumentParser();p.add_argument('--stock',type=Path,required=True);p.add_argument('--ownership',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
class ELF:
 def __init__(self,path):
  self.path=path;self.b=path.read_bytes();self.machine=struct.unpack_from('<H',self.b,18)[0];cl=self.b[4]
  assert self.b[:4]==b'\x7fELF' and self.b[5]==1
  self.segs=[]
  if cl==2:
   o=struct.unpack_from('<Q',self.b,32)[0];sz,n=struct.unpack_from('<HH',self.b,54)
   for i in range(n):
    ty,fl,of,va,pa,fs,ms,al=struct.unpack_from('<IIQQQQQQ',self.b,o+i*sz)
    if ty==1:self.segs.append((of,va,pa,fs,ms,fl))
  elif cl==1:
   o=struct.unpack_from('<I',self.b,28)[0];sz,n=struct.unpack_from('<HH',self.b,42)
   for i in range(n):
    ty,of,va,pa,fs,ms,fl,al=struct.unpack_from('<8I',self.b,o+i*sz)
    if ty==1:self.segs.append((of,va,pa,fs,ms,fl))
  else:raise ValueError('unsupportedclass')
 def read(self,va,n):
  for of,v,pa,fs,ms,fl in self.segs:
   if v<=va and va+n<=v+fs:return self.b[of+va-v:of+va-v+n]
  raise ValueError(f'Not file backed:{va:x}')
 def excerpt(self,va,n):
  assert self.machine in (183,243)
  md=Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN) if self.machine==183 else Cs(CS_ARCH_RISCV,CS_MODE_RISCV32|CS_MODE_RISCVC)
  md.skipdata=True
  return '\n'.join(f'{ad:08x} {mn} {op}'.rstrip() for ad,sz,mn,op in md.disasm_lite(self.read(va,n),va))
imgs={'TZ':ELF(args.stock/'tz_b.bin'),'XBL_ARM64':ELF(args.ownership/'xbl-embedded-0x55f94.elf'),'CPUCP':ELF(args.ownership/'cpucp_b.bin')}
ranges={'TZ':[(0x1570f250,0x6c),(0x15707aa4,0x284),(0xd8317c00,0x18c),(0xd8319dac,0x58),(0xd831a870,0x94),(0xd831b7bc,0x98),(0xd831ba78,0xc),(0xd830fcac,0x14),(0xd831c2e8,0x18)],'XBL_ARM64':[(0x1486f1a4,0x8c)],'CPUCP':[(0x17d20000,0x36),(0x17d24656,0x6a),(0x17d246c0,0x23a),(0x17d24c36,0x1a),(0x17d248fa,0x1a),(0x17d24b28,0x110),(0xd82e7270,0xd0),(0xd82e7340,0x160)]}
manifest=[]
for label,img in imgs.items():
 manifest.append({'label':label,'path':str(img.path),'sha256':hashlib.sha256(img.b).hexdigest(),'machine':img.machine,'segments':[{'file_offset':hex(o),'va':hex(v),'pa':hex(pa),'filesz':hex(f),'memsz':hex(m),'flags':fl} for o,v,pa,f,m,fl in img.segs]})
 lines=[]
 for ad,n in ranges[label]:lines.extend([f'Image{label};range{ad:08x}+{n:x}',img.excerpt(ad,n),''])
 (args.output/(label.lower()+'-excerpts.txt')).write_text('\n'.join(lines))
cp=imgs['CPUCP'];data={'saved_ep88_table':{'va':'0x17d0c37c','file_values':list(struct.unpack('<4I',cp.read(0x17d0c37c,16))),'note':'File initializer only. Runtime modifications not excluded. Cluster1..3consumers use indexes1..3.'},'init_separate_algorithm_gates':{'c1':'0x17d2a718','second':'0x17d2a71c','both_initialized_to':1,'instruction_vas':['0xd82e72c0','0xd82e72c2']},'tz_domain_table':[]}
tz=imgs['TZ']
data['tz_request_midr_dispatch']={'entry_va':'0xd8317c94','mask':hex(struct.unpack('<Q',tz.read(0xd8317e30,8))[0]),'targets':[{'midr':hex(struct.unpack('<Q',tz.read(va,8))[0]),'consumer':hex(consumer)} for va,consumer in [(0xd8317e38,0xd8319dac),(0xd8317e40,0xd831a870),(0xd8317e48,0xd831ba78),(0xd8317e58,0xd831b7bc),(0xd8317e70,0xd831ba78)]]}
data['consumer_address_literals']=[{'literal_va':hex(va),'value':hex(struct.unpack('<Q',tz.read(va,8))[0])} for va in [0xd831a358,0xd831a360,0xd831a368,0xd831a370,0xd831ab70,0xd831ab78,0xd831ab80,0xd831ab88,0xd831ab90,0xd831ba20,0xd831ba28,0xd831ba30,0xd831ba38,0xd831ba40,0xd831ba48]]
data['core_distinctions']=[{'cpu':'Cortex-A510','masked_midr':'0x410fd460','consumer':'0xd8319dac','request_type':0,'register_encoding':'S3_0_C15_C1_4','field':'16:6','field_semantics':'Not established in bounded source pass'}, {'cpu':'Cortex-A710','masked_midr':'0x410fd470','consumer':'0xd831a870','request_type':1,'register_encoding':'S3_0_C15_C1_4','field':'63:61','field_semantics':'No A710-specific field source obtained; do not transfer X3 definition'}, {'cpu':'Cortex-A715','masked_midr':'0x410fd4d0','consumer':None,'note':'Absent from this exact request dispatch. No A715-specific request consumer established.'}, {'cpu':'Cortex-X3','masked_midr':'0x410fd4e0','consumer':'0xd831b7bc','request_types':[1,2],'field_semantics':'Arm X3 TRM proves CMC_MIN_WAYS and PF_MODE, as below'}]
data['prime_adaptive_effects']=[{'type':1,'selector_address':'0x17d08c58','payload_address':'0x17d08c78','consumer':'0xd831b7bc','register_encoding':'S3_0_C15_C1_4','field':'63:61','documented_name':'IMP_CPUECTLR_EL1.CMC_MIN_WAYS','write_va':'0xd831b7f4'}, {'type':2,'selector_address':'0x17d08d10','payload_address':'0x17d08d18','consumer':'0xd831b804','register_encoding':'S3_0_C15_C1_5','field':'14:11','documented_name':'IMP_CPUECTLR2_EL1.PF_MODE','write_va':'0xd831b830'}]
for i in range(4):
 va=0x157afc00+i*0x38;row=struct.unpack('<7Q',tz.read(va,0x38));name=tz.read(row[0],32).split(b'\x00')[0].decode(errors='replace');data['tz_domain_table'].append({'entry_va':hex(va),'name':name,'domain':row[1],'epss_base':hex(row[2]),'pll_base':hex(row[3]),'other_addresses':[hex(x) for x in row[4:]]})
(args.output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');(args.output/'decoded-tables.json').write_text(json.dumps(data,indent=2)+'\n');print(json.dumps(data,indent=2))
