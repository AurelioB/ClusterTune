"""Bounded static graph/data-reference audit; host-only. Capstone 5.0.6 required.
Usage: python inspect_graph.py cpucp_b.bin OUTDIR
"""
import argparse,struct,json,hashlib,re
from pathlib import Path
from capstone import Cs,CS_ARCH_RISCV,CS_MODE_RISCV32,CS_MODE_RISCVC,CS_ARCH_ARM64,CS_MODE_LITTLE_ENDIAN
ap=argparse.ArgumentParser();ap.add_argument('image',type=Path);ap.add_argument('outdir',type=Path);args=ap.parse_args()
b=args.image.read_bytes();sha=hashlib.sha256(b).hexdigest();assert sha=='19de4a8dd45eaa80ca2dd33d7a73661373805efd3970c994f2274e920b1d3808'
po=struct.unpack_from('<I',b,28)[0];ps,n=struct.unpack_from('<HH',b,42)
segs=[struct.unpack_from('<8I',b,po+i*ps) for i in range(n)]
def read(a,z):
 for t,o,v,pa,fs,ms,fl,al in segs:
  if t==1 and v<=a and a+z<=v+fs:return b[o+a-v:o+a-v+z]
 raise KeyError(a)
md=Cs(CS_ARCH_RISCV,CS_MODE_RISCV32|CS_MODE_RISCVC);md.skipdata=True
ins=[]
for t,o,v,pa,fs,ms,fl,al in segs:
 if t==1 and fl&1:ins.extend(md.disasm_lite(b[o:o+fs],v))
by={a:(s,m,o) for a,s,m,o in ins};execstarts={a for a,s,m,o in ins}
# Conservative straight-line register constants. Branches and returns clear state.
regs={'zero':0};refs=[];calls=[];helperaddr=[];imm83=[];linear_indirect=[]
for a,z,m,o in ins:
 f=[v.strip() for v in o.split(',')];state_before=regs.copy();dst=None;generated=None
 def r(k):return regs.get(k)
 try:
  if m in ['jal','c.jal','c.j','j']:
   dst=(a+int(f[-1],0))&0xffffffff
   if m in ['jal','c.jal']:calls.append({'call_va':hex(a),'target':hex(dst),'encoding':m})
  elif m in ['jalr','c.jalr','c.jr','jr','ret']:
   if m=='jalr' and len(f)==3 and r(f[1]) is not None:dst=(r(f[1])+int(f[2],0))&0xfffffffe
   elif m in ['c.jalr','c.jr','jr'] and r(f[0]) is not None:dst=r(f[0])&0xfffffffe
   if dst is not None:
    linear_indirect.append({'call_va':hex(a),'target':hex(dst),'encoding':m})
    if m in ['jalr','c.jalr']:calls.append({'call_va':hex(a),'target':hex(dst),'encoding':m})
  if m in ['lui','c.lui']:generated=(int(f[1],0)<<12)&0xffffffff
  elif m=='auipc':generated=(a+(int(f[1],0)<<12))&0xffffffff
  elif m in ['addi','addiw'] and r(f[1]) is not None:generated=(r(f[1])+int(f[2],0))&0xffffffff
  elif m=='c.addi' and r(f[0]) is not None:generated=(r(f[0])+int(f[1],0))&0xffffffff
  elif m in ['li','c.li']:generated=int(f[1],0)&0xffffffff
  elif m in ['mv','c.mv'] and r(f[1]) is not None:generated=r(f[1])
  elif m in ['add','c.add']:
   lhs,rhs=(f[1],f[2]) if len(f)==3 else (f[0],f[1])
   if r(lhs) is not None and r(rhs) is not None:generated=(r(lhs)+r(rhs))&0xffffffff
  elif m in ['slli','c.slli']:
   sr,imm=(f[1],f[2]) if len(f)==3 else(f[0],f[1])
   if r(sr) is not None:generated=(r(sr)<<int(imm,0))&0xffffffff
  elif m in ['srli','c.srli']:
   sr,imm=(f[1],f[2]) if len(f)==3 else(f[0],f[1])
   if r(sr) is not None:generated=r(sr)>>int(imm,0)
  if m in ['lw','c.lw','lbu','lh','lhu','lb','sw','c.sw','sb','sh']:
   match=re.fullmatch(r'([^()]+)\(([^()]+)\)',f[1])
   if match and r(match[2]) is not None:
    ea=(r(match[2])+int(match[1],0))&0xffffffff
    if any(lo<=ea<hi for lo,hi in[(0x17d0d528,0x17d0d570),(0x17d28658,0x17d28668)]):refs.append({'instruction_va':hex(a),'instruction':m+' '+o,'effective_address':hex(ea),'value_if_known':hex(r(f[0])) if r(f[0]) is not None else None})
    if m in ['lw','c.lw','lbu']:
     try:generated=int.from_bytes(read(ea,1 if m=='lbu' else 4),'little')
     except KeyError:pass
  if generated is not None:
   if generated==0xd82e75d4:helperaddr.append({'instruction_va':hex(a),'instruction':m+' '+o})
   if generated==0x83:imm83.append({'instruction_va':hex(a),'instruction':m+' '+o})
   if generated in [0x17d0d528,0x17d28658]:refs.append({'instruction_va':hex(a),'instruction':m+' '+o,'materialized_address':hex(generated)})
  # Store/branch have no destination; other unsupported operations invalidate output.
  no_dest=m in ['sw','c.sw','sb','sh','c.swsp','j','c.j','c.jr','jr','ret','c.nop','nop'] or m.startswith(('b','c.b','csr'))
  if not no_dest and f and re.fullmatch(r'[ast][0-9]+|ra|sp|gp|tp|zero',f[0]):
   if generated is not None:regs[f[0]]=generated
   else:regs.pop(f[0],None)
  if m in ['jal','c.jal','jalr','c.jalr']:
   for k in ['ra','a0','a1','a2','a3','a4','a5','a6','a7','t0','t1','t2','t3','t4','t5','t6']:regs.pop(k,None)
  if m.startswith(('b','c.b')) or m in ['j','c.j','c.jr','jr','ret']:regs={'zero':0}
  regs['zero']=0
 except (IndexError,ValueError):regs={'zero':0}
# Catch all fixed PC-relative calls independent of conservative state resets.
for i,(a,z,m,o) in enumerate(ins):
 if m=='jalr' and i:
  pa,pz,pm,pp=ins[i-1];f=[v.strip() for v in o.split(',')];pf=[v.strip() for v in pp.split(',')]
  if pm=='auipc' and pa+pz==a and len(f)==3 and f[1]==pf[0]:
   d=(pa+(int(pf[1],0)<<12)+int(f[2],0))&0xfffffffe
   c={'call_va':hex(a),'target':hex(d),'encoding':m}
   if c not in calls:calls.append(c)
want=[0xd82e75d4,0xd82e5f5a,0xd82e5836,0xd82e6862,0xd82e7198,0xd82e7270,0x17d2067a,0x17d22f92,0x17d23492]
result={'sha256':sha,'instructions':len(ins),'resolved_calls_to_selected_init_and_registration_targets':{hex(w):[c for c in calls if int(c['target'],16)==w] for w in want},'linear_constant_indirect_transfers_to_registration_helper':[c for c in linear_indirect if int(c['target'],16)==0xd82e75d4],'materialized_registration_helper_address':helperaddr,'constant_0x83_materializations':imm83,'table_and_advertisement_references':refs,'literal_registration_helper_pointer_offsets':[hex(i) for i in range(len(b)-3) if b[i:i+4]==struct.pack('<I',0xd82e75d4)],'literal_registration_table_pointer_offsets':[hex(i) for i in range(len(b)-3) if b[i:i+4]==struct.pack('<I',0x17d0d528)],'module_event_table':{str(i):hex(struct.unpack('<I',read(0x17d0df14+4*i,4))[0]) for i in range(13)},'limitations':'Conservative local constant tracking is not a whole-program pointer proof. Calls across branches, loaded runtime pointers, computed addresses and opaque firmware input can remain unresolved.'}
args.outdir.mkdir(exist_ok=True,parents=True);(args.outdir/'graph-analysis.json').write_text(json.dumps(result,indent=2)+'\n')
ranges=[('main init',0x17d22f92,0x17d23074),('SCMI initializer',0xd82e5f5a,0xd82e6022),('module event dispatcher',0x17d2355e,0x17d23648),('DCVS init',0xd82e7270,0xd82e7340),('advertised list accessor',0xd82e7462,0xd82e74c0),('vendor dispatcher',0x17d06808,0x17d068d2),('registration helper',0xd82e75d4,0xd82e760c)]
lines=[]
for label,lo,hi in ranges:
 lines.append(f'[{label}: {lo:#x}..{hi:#x})');lines.extend(f'{a:08x} {m} {o}' for a,z,m,o in ins if lo<=a<hi);lines.append('')
(args.outdir/'init-excerpts.txt').write_text('\n'.join(lines))
print(json.dumps({'calls_to_registration':result['resolved_calls_to_selected_init_and_registration_targets']['0xd82e75d4'],'constant_83':imm83,'selected_refs':refs},indent=2))
