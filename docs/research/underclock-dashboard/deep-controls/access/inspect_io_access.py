#!/usr/bin/env python3
"""Decode and emulate rejected stock SCM IO accesses on host scratch memory.
No device/network/MMIO access. Unicorn 2.1.4, Capstone 5.0.6.
Only addresses absent from the recorded exact-address allowlist are simulated.
"""
import argparse,hashlib,json,struct
from pathlib import Path
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE,UC_HOOK_MEM_WRITE
from unicorn.arm64_const import UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X30,UC_ARM64_REG_SP,UC_ARM64_REG_PC
p=argparse.ArgumentParser(description=__doc__);p.add_argument('image',type=Path);p.add_argument('output',type=Path);args=p.parse_args()
b=args.image.read_bytes();sha=hashlib.sha256(b).hexdigest()
assert sha=='917e26b15114884cd54bc47fdb40ae43b63ee6281ce74f217d3990dcdfa1d206'
o=struct.unpack_from('<Q',b,32)[0];size,count=struct.unpack_from('<HH',b,54)
segments=[struct.unpack_from('<IIQQQQQQ',b,o+i*size) for i in range(count)]
def read(ad,n):
 for ty,fl,of,va,pa,fs,ms,al in segments:
  if ty==1 and va<=ad and ad+n<=va+fs:return b[of+ad-va:of+ad-va+n]
 raise ValueError(hex(ad))
rows=[struct.unpack('<IIQ',read(0x157aa918+i*16,16)) for i in range(53)]
candidates=[base+offset for base in (0x17d91000,0x17d92000,0x17d93000) for offset in (0x88,0xbc,0x100)]
assert not set(candidates)&{row[0] for row in rows},'Candidate allowlisted: do not emulate'
results=[]
for addr in candidates:
 for operation,entry,end in [('write',0x1571cab8,0x1571cc2c),('read',0x1571cc30,0x1571cd24)]:
  u=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
  for page in [0x1571c000,0x15678000,0x157aa000]:
   u.mem_map(page,4096);u.mem_write(page,read(page,4096))
  u.mem_map(0x10000000,0x2000);stop=0x10001000
  u.reg_write(UC_ARM64_REG_SP,0x10001000);u.reg_write(UC_ARM64_REG_X30,stop)
  u.reg_write(UC_ARM64_REG_X0,addr);u.reg_write(UC_ARM64_REG_X1,0 if operation=='write' else 0x10000000)
  visited=[];writes=[]
  def instruction(emu,ad,n,context):
   if ad==stop:emu.emu_stop();return
   if not (entry<=ad<end or 0x156781b0<=ad<0x156781d0):raise RuntimeError('Unexpected code '+hex(ad))
   visited.append(hex(ad))
   # Unicorn does not model this platform's pointer-authentication instructions.
   # Skip only the exact PACIA prologue / RETAA epilogue; semantic checks remain native.
   if ad in (0x1571cabc,0x1571cc34):
    assert bytes(emu.mem_read(ad,4))==bytes.fromhex('fe03c1da')
    emu.reg_write(UC_ARM64_REG_PC,ad+4)
   elif ad in (0x1571cc28,0x1571cd04):
    assert bytes(emu.mem_read(ad,4))==bytes.fromhex('ff0b5fd6')
    emu.reg_write(UC_ARM64_REG_PC,emu.reg_read(UC_ARM64_REG_X30))
  def write(emu,access,ad,n,val,context):
   if not 0x10000000<=ad or ad+n>0x10001000:raise RuntimeError('Non-scratch write '+hex(ad))
   writes.append({'address':hex(ad),'bytes':n,'value':hex(val)})
  u.hook_add(UC_HOOK_CODE,instruction);u.hook_add(UC_HOOK_MEM_WRITE,write)
  u.emu_start(entry,stop,count=1000)
  assert u.reg_read(UC_ARM64_REG_PC)==stop,'Instruction budget exhausted'
  status=struct.unpack('<i',struct.pack('<I',u.reg_read(UC_ARM64_REG_X0)&0xffffffff))[0]
  assert status==-1,status
  results.append({'address':hex(addr),'operation':operation,'return_s32':status,'instructions':len(visited),'visited':visited if addr==candidates[0] else [],'writes':writes})
args.output.mkdir(parents=True,exist_ok=True)
(args.output/'io-allowlist.json').write_text(json.dumps({'image_sha256':sha,'table':'0x157aa918','count':53,'rows':[{'address':hex(a),'attributes':hex(f),'callback':hex(c)} for a,f,c in rows],'method':'Bounded host emulation of absent-address rejection only; exact PACIA/RETAA instructions substituted for unsupported pointer authentication; no firmware SMC, device or MMIO execution','results':results},indent=2)+'\n')
md=Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN);lines=[]
for ad,n in [(0x156781b0,0x20),(0x1571cab8,0x288)]:
 lines.append(f'# range {ad:#x}+{n:#x}')
 lines.extend(f'{a:08x} {m} {op}'.rstrip() for a,sz,m,op in md.disasm_lite(read(ad,n),ad))
(args.output/'io-handler-excerpts.txt').write_text('\n'.join(lines)+'\n')
print(json.dumps({'allowlist_entries':len(rows),'rejected_host_cases':len(results),'statuses':sorted({r['return_s32'] for r in results})}))
