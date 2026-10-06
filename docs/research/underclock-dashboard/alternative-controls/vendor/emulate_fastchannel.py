"""Offline firmware function emulation; never connects to or writes a device."""
from pathlib import Path
import argparse,struct,json,hashlib
from unicorn import Uc,UC_ARCH_RISCV,UC_MODE_RISCV32,UC_HOOK_CODE,UC_HOOK_MEM_WRITE
from unicorn.riscv_const import UC_RISCV_REG_A0,UC_RISCV_REG_SP,UC_RISCV_REG_RA,UC_RISCV_REG_PC
p=argparse.ArgumentParser();p.add_argument('firmware',type=Path);p.add_argument('--poison',type=lambda x:int(x,0),default=0xa5);args=p.parse_args()
b=args.firmware.read_bytes();assert hashlib.sha256(b).hexdigest()=='19de4a8dd45eaa80ca2dd33d7a73661373805efd3970c994f2274e920b1d3808', 'Firmware version differs';phoff=struct.unpack_from('<I',b,28)[0];phsize,n=struct.unpack_from('<HH',b,42)
u=Uc(UC_ARCH_RISCV,UC_MODE_RISCV32);mapped=set()
for i in range(n):
 typ,off,va,pa,fs,ms,flags,al=struct.unpack_from('<8I',b,phoff+i*phsize)
 if typ!=1 or ms==0:continue
 for page in range(va&~4095,(va+ms+4095)&~4095,4096):
  if page not in mapped:u.mem_map(page,4096);mapped.add(page)
 if fs:u.mem_write(va,b[off:off+fs])
u.mem_map(0x10000000,0x30000);u.mem_write(0x10000000,bytes([args.poison])*0x10000)
request=0x10010000;stop=0x10020000
u.reg_write(UC_RISCV_REG_SP,0x10008000);u.reg_write(UC_RISCV_REG_RA,stop);u.reg_write(UC_RISCV_REG_A0,request)
u.mem_write(request,bytes(128));u.mem_write(request+0x18,struct.pack('<III',(0x13<<10)|11,0,5))
# Model the firmware's own callback-table initialization and domain count.
u.mem_write(0x17d0d4dc,b[0x13610:0x13610+0x48]);u.mem_write(0x17d0d524,struct.pack('<I',4))
writes=[];steps=[]
def code(u,addr,size,data):
 steps.append(addr)
 if addr==stop:u.emu_stop()
def write(u,access,addr,size,value,data):
 if request<=addr<request+128:writes.append({'pc':hex(u.reg_read(UC_RISCV_REG_PC)),'address':hex(addr),'size':size,'value':hex(value)})
u.hook_add(UC_HOOK_CODE,code);u.hook_add(UC_HOOK_MEM_WRITE,write)
u.emu_start(0x17d0628a,stop,count=2000)
result={'firmware_sha256':hashlib.sha256(b).hexdigest(),'method':'offline Unicorn 2.1.4 RV32; loaded ELF segments; synthetic request; callback table initialized from image; stack fill controls descriptor data','start':'0x17d0628a','request_protocol':'0x13','command':11,'payload':[0,5],'poison_byte':hex(args.poison),'reached_return':u.reg_read(UC_RISCV_REG_PC)==stop,'instructions':len(steps),'callback_visited':'0x17d20dc6' if 0x17d20dc6 in steps else None,'a0':u.reg_read(UC_RISCV_REG_A0),'response_length':struct.unpack('<I',u.mem_read(request+0x14,4))[0],'response_words':list(struct.unpack('<12I',u.mem_read(request+0x1c,48))),'response_writes':writes}
print(json.dumps(result,indent=2))
