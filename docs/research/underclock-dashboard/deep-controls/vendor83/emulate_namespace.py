"""Offline namespace model: execute stock SCMI init and helper, never a device.
This is not full-boot emulation. Its table is populated from four statically
identified stock registrations; arbitrary external table modification is excluded.
Requires unicorn==2.1.4. Usage: python emulate_namespace.py cpucp_b.bin
"""
import struct,json,argparse,hashlib
from pathlib import Path
from unicorn import Uc,UC_ARCH_RISCV,UC_MODE_RISCV32,UC_HOOK_CODE,UC_HOOK_MEM_WRITE
from unicorn.riscv_const import UC_RISCV_REG_PC,UC_RISCV_REG_SP,UC_RISCV_REG_RA,UC_RISCV_REG_A0,UC_RISCV_REG_A1,UC_RISCV_REG_A2
ap=argparse.ArgumentParser();ap.add_argument('image',type=Path);args=ap.parse_args();b=args.image.read_bytes();sha=hashlib.sha256(b).hexdigest();assert sha=='19de4a8dd45eaa80ca2dd33d7a73661373805efd3970c994f2274e920b1d3808'
u=Uc(UC_ARCH_RISCV,UC_MODE_RISCV32);mapped=set();po=struct.unpack_from('<I',b,28)[0];ps,n=struct.unpack_from('<HH',b,42)
for i in range(n):
 typ,off,va,pa,fs,ms,flags,al=struct.unpack_from('<8I',b,po+i*ps)
 if typ!=1 or ms==0:continue
 for page in range(va&~4095,(va+ms+4095)&~4095,4096):
  if page not in mapped:u.mem_map(page,4096);mapped.add(page)
 if fs:u.mem_write(va,b[off:off+fs])
for page in [0x17d09000,0x17d10000,0x17d90000,0x17d91000]:
 if page not in mapped:u.mem_map(page,4096);mapped.add(page)
u.mem_map(0x10000000,0x30000);request=0x10010000;stop=0x10020000;steps=[];writes=[]
def code(u,addr,size,data):
 steps.append(addr)
 if addr==stop:u.emu_stop()
def write(u,access,addr,size,value,data):
 if 0x17d0d528<=addr<0x17d0d570:writes.append({'pc':hex(u.reg_read(UC_RISCV_REG_PC)),'address':hex(addr),'size':size,'value':hex(value)})
u.hook_add(UC_HOOK_CODE,code);u.hook_add(UC_HOOK_MEM_WRITE,write)
def call(pc,a0=0,a1=0,a2=0,count=10000):
 u.reg_write(UC_RISCV_REG_SP,0x10008000);u.reg_write(UC_RISCV_REG_RA,stop)
 for reg,val in [(UC_RISCV_REG_A0,a0),(UC_RISCV_REG_A1,a1),(UC_RISCV_REG_A2,a2)]:u.reg_write(reg,val)
 start=len(steps);u.emu_start(pc,stop,count=count);assert u.reg_read(UC_RISCV_REG_PC)==stop,hex(u.reg_read(UC_RISCV_REG_PC))
 return {'instructions':len(steps)-start,'a0':u.reg_read(UC_RISCV_REG_A0)}
init=call(0xd82e5f5a)
init['advertised_count']=u.mem_read(0x17d28664,1)[0];init['advertised_protocols']=[hex(v) for v in u.mem_read(0x17d28658,6)];init['runtime_vendor_table_after_scmi_init']=list(struct.unpack('<18I',u.mem_read(0x17d0d528,72)))
registrations=[(0x87,0x17d201a8),(0x86,0xd82e5736),(0x80,0xd82e60fe),(0x84,0xd82e714a)]
for protocol,handler in registrations:call(0xd82e75d4,protocol,handler)
table=list(struct.unpack('<18I',u.mem_read(0x17d0d528,72)));responses=[]
for command in range(256):
 u.mem_write(request,bytes(512));u.mem_write(request+0x18,struct.pack('<I',(0x83<<10)|command))
 ret=call(0x17d06808,request);length=struct.unpack('<I',u.mem_read(request+0x14,4))[0]
 words=list(struct.unpack('<'+'I'*((length-4)//4),u.mem_read(request+0x1c,length-4)))
 responses.append({'command':command,'response_length':length,'status':struct.unpack('<i',struct.pack('<I',words[0]))[0],'response_words':words,**ret})
result={'firmware_sha256':sha,'method':'Host-only Unicorn2.1.4; stock SCMI initializer with synthetic zero-valued MMIO pages, then stock registration helper invoked with four statically resolved registrations. This is not full boot emulation and cannot exclude external table mutation.','scmi_initializer':init,'modeled_registrations':[{'protocol':hex(p),'handler':hex(h)} for p,h in registrations],'runtime_vendor_table_after_modeled_registrations':[hex(v) for v in table],'registration_table_writes':writes,'protocol83_generic_responses':responses[:3],'protocol83_commands3_to255':{'tested':len(responses[3:]),'all_status_minus1':all(r['status']==-1 for r in responses[3:]),'all_response_length8':all(r['response_length']==8 for r in responses[3:]),'examples':[responses[3],responses[16],responses[255]]}}
print(json.dumps(result,indent=2))
