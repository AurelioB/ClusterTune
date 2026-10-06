import pathlib,struct,json,hashlib,argparse
parser=argparse.ArgumentParser(description='Static constant search only; does not query or modify a device.')
parser.add_argument('artifact_dir',type=pathlib.Path)
parser.add_argument('output',type=pathlib.Path)
args=parser.parse_args()
from capstone import Cs,CS_ARCH_ARM64,CS_ARCH_ARM,CS_MODE_THUMB,CS_MODE_LITTLE_ENDIAN
SRC=args.artifact_dir
CONSTANTS={'FCAP':0x46434150,'DCVS':0x44435653,'THML':0x54484D4C,'LMH_SIP_DCVSH':0x02001310,'LMH_SMC64_DCVSH':0x42001310,'LMH_FAST64_DCVSH':0xC2001310,'ENBL_calibration':0x454E424C}
def segments(b):
 assert b[:4]==b'\x7fELF'
 if b[4]==2:
  off=struct.unpack_from('<Q',b,32)[0];sz,num=struct.unpack_from('<HH',b,54)
  return [struct.unpack_from('<IIQQQQQQ',b,off+i*sz) for i in range(num)]
 off=struct.unpack_from('<I',b,28)[0];sz,num=struct.unpack_from('<HH',b,42)
 out=[]
 for i in range(num):
  typ,offset,va,pa,fs,ms,flags,align=struct.unpack_from('<8I',b,off+i*sz);out.append((typ,flags,offset,va,pa,fs,ms,align))
 return out
result=[]
for filename in ('tz_b.bin','aop_b.bin','xbl_b.bin'):
 b=(SRC/filename).read_bytes();segs=segments(b);item={'file':filename,'sha256':hashlib.sha256(b).hexdigest(),'raw_constant_hits':{},'code_constant_hits':[]}
 for name,val in CONSTANTS.items():
  needle=struct.pack('<I',val);positions=[];p=0
  while True:
   p=b.find(needle,p)
   if p<0:break
   maps=[hex(va+p-off) for typ,flags,off,va,pa,fs,ms,align in segs if typ==1 and off<=p<off+fs]
   positions.append({'file_offset':hex(p),'virtual_addresses':maps});p+=4
  item['raw_constant_hits'][name]=positions
 machine=struct.unpack_from('<H',b,18)[0]
 item['elf_machine']=machine
 item['search_limitations']='Literal bytes and selected immediate constructions only; absence does not establish unsupported commands. No complete control-flow or firmware dispatch analysis.'
 if machine not in (183,40):
  item['code_scan']='skipped: unrecognized code architecture/container'
  result.append(item)
  print(filename,item['code_scan'],flush=True)
  continue
 arm64=machine==183
 item['code_scan']='AArch64 executable segments' if arm64 else 'Thumb executable segments (mode inferred from AOP entry point)'
 md=Cs(CS_ARCH_ARM64 if arm64 else CS_ARCH_ARM,CS_MODE_LITTLE_ENDIAN if arm64 else CS_MODE_THUMB);md.skipdata=True
 instructions=0
 for typ,flags,off,va,pa,fs,ms,align in segs:
  if typ!=1 or not flags&1:continue
  state={}
  for address,size,mnemonic,operands in md.disasm_lite(b[off:off+fs],va):
   instructions+=1
   fields=[f.strip() for f in operands.split(',')]
   if mnemonic in ('mov','movz','movw') and len(fields)==2 and fields[1].startswith('#'):
    try:state[fields[0]]=(int(fields[1][1:],0),address)
    except ValueError:continue
   elif mnemonic=='movt' and fields[0] in state:
    val,last=state[fields[0]]
    if address-last>64:continue
    try:val=(val&65535)|(int(fields[1][1:],0)<<16)
    except ValueError:continue
    state[fields[0]]=(val,address)
   elif mnemonic=='movk' and fields[0] in state:
    val,last=state[fields[0]]
    if address-last>64:continue
    try:imm=int(fields[1][1:],0);shift=int(fields[2].split('#')[1]) if len(fields)>2 else 0
    except (ValueError,IndexError):continue
    val=(val&~(65535<<shift))|(imm<<shift);state[fields[0]]=(val,address)
   else:continue
   val,_=state[fields[0]]
   for name,needle in CONSTANTS.items():
    if val==needle:item['code_constant_hits'].append({'constant':name,'virtual_address':hex(address),'file_offset':hex(off+address-va),'instruction':mnemonic+' '+operands})
 item['decoded_instructions']=instructions;result.append(item)
 print(filename,'instructions',instructions,'rawhits',{k:len(v) for k,v in item['raw_constant_hits'].items()},'codehits',item['code_constant_hits'],flush=True)
args.output.write_text(json.dumps(result,indent=2)+'\n')
