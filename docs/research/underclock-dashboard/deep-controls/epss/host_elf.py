from pathlib import Path
import struct,hashlib
from capstone import *
class ELF:
 def __init__(self,path):
  self.path=Path(path);self.b=self.path.read_bytes();assert self.b[:4]==b'\x7fELF';self.cl=self.b[4];self.machine=struct.unpack_from('<H',self.b,18)[0];self.segs=[]
  off=struct.unpack_from('<Q' if self.cl==2 else '<I',self.b,32 if self.cl==2 else 28)[0];sz,n=struct.unpack_from('<HH',self.b,54 if self.cl==2 else 42)
  for i in range(n):
   r=struct.unpack_from('<IIQQQQQQ' if self.cl==2 else '<8I',self.b,off+i*sz)
   if self.cl==2:t,fl,f,v,p,fs,ms,al=r
   else:t,f,v,p,fs,ms,fl,al=r
   if t==1:self.segs.append((f,v,p,fs,ms,fl))
 def va(self,of):
  for f,v,p,fs,ms,fl in self.segs:
   if f<=of<f+fs:return v+of-f
 def read(self,va,n):
  for f,v,p,fs,ms,fl in self.segs:
   if v<=va and va+n<=v+fs:return self.b[f+va-v:f+va-v+n]
  raise ValueError(hex(va))
 def text(self,va,n=160):return self.read(va,n).split(b'\x00')[0].decode(errors='replace')
 def dis(self,va,n):
  md=Cs(CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN) if self.machine==183 else Cs(CS_ARCH_RISCV,CS_MODE_RISCV32|CS_MODE_RISCVC);md.skipdata=True
  return '\n'.join(f'{ad:08x} {mn} {op}'.rstrip() for ad,z,mn,op in md.disasm_lite(self.read(va,n),va))
 def refs(self,a):
  out=[];p=struct.pack('<Q' if self.cl==2 else '<I',a);st=0
  while True:
   st=self.b.find(p,st)
   if st<0:return out
   out.append((st,self.va(st)));st+=1
