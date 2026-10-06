"""Read-only RV32 ELF analysis. Usage: python inspect_vendor.py IMAGE OUTDIR.
Requires capstone==5.0.6. Does not contact a device or copy firmware bytes.
"""
import argparse,hashlib,json,struct
from pathlib import Path
from capstone import Cs,CS_ARCH_RISCV,CS_MODE_RISCV32,CS_MODE_RISCVC
p=argparse.ArgumentParser();p.add_argument('image',type=Path);p.add_argument('outdir',type=Path);args=p.parse_args()
b=args.image.read_bytes();sha=hashlib.sha256(b).hexdigest()
assert sha=='19de4a8dd45eaa80ca2dd33d7a73661373805efd3970c994f2274e920b1d3808', 'Firmware version differs; conclusions require review'
phoff=struct.unpack_from('<I',b,28)[0];phsize,n=struct.unpack_from('<HH',b,42)
segs=[struct.unpack_from('<8I',b,phoff+i*phsize) for i in range(n)]
def read(va,size):
 for typ,off,start,pa,fs,ms,flags,al in segs:
  if typ==1 and start<=va and va+size<=start+fs:return b[off+va-start:off+va-start+size]
 raise ValueError('VA is not file-backed')
md=Cs(CS_ARCH_RISCV,CS_MODE_RISCV32|CS_MODE_RISCVC);md.skipdata=True
ins=[]
for typ,off,va,pa,fs,ms,flags,al in segs:
 if typ==1 and flags&1:ins.extend(md.disasm_lite(b[off:off+fs],va))
calls=[]
for i,(ad,sz,mn,ops) in enumerate(ins):
 f=[x.strip() for x in ops.split(',')];dst=None
 if mn in ('jal','c.jal','c.j'):
  try:dst=(ad+int(f[-1],0))&0xffffffff
  except ValueError:pass
 elif mn=='jalr' and i:
  pad,psz,pmn,pops=ins[i-1];pf=[x.strip() for x in pops.split(',')]
  if pmn=='auipc' and pad+psz==ad and len(f)==3 and f[1]==pf[0]:
   dst=(pad+(int(pf[1],0)<<12)+int(f[2],0))&0xfffffffe
 if dst==0xd82e75d4:
  calls.append({'call_va':hex(ad),'encoding':mn,'destination':hex(dst),'preceding_instructions':[f'{x:08x} {m} {o}' for x,s,m,o in ins[max(0,i-4):i+1]]})
regs=[{'protocol':'0x87','handler':'0x17d201a8','call_va':'0x17d206fc'}, {'protocol':'0x86','handler':'0xd82e5736','call_va':'0xd82e588a'}, {'protocol':'0x80','handler':'0xd82e60fe','call_va':'0xd82e687e'}, {'protocol':'0x84','handler':'0xd82e714a','call_va':'0xd82e71aa'}]
assert {x['call_va'] for x in calls}=={x['call_va'] for x in regs}
cmdnames=['LOG_LEVEL','FLUSH_LOG','MEM_GROUP','MONITOR','COMMON_EVENT_MAP','GROUP_EVENT_MAP','ADAPTIVE_LOW_FREQ','ADAPTIVE_HIGH_FREQ','ADAPTIVE_CURRENT_FREQ','IPM_CEIL','FE_STALL_FLOOR','BE_STALL_FLOOR','WB_PCT','IPM_FILTER','FREQ_SCALE_PCT','FREQ_SCALE_CEIL_MHZ','FREQ_SCALE_FLOOR_MHZ','SAMPLE_MS','MON_FREQ_MAP','MIN_FREQ','MAX_FREQ','CURRENT_FREQ','START_TIMER','STOP_TIMER','TIMESTAMP']
cmdids=[3,4,*range(16,39)]
memlat=[]
for cid,name in zip(cmdids,cmdnames):
 target=struct.unpack('<I',read(0x17d28288+4*(cid-3),4))[0]
 memlat.append({'command':cid,'name_from_related_source':name,'handler_branch':hex(target)})
fc=struct.unpack('<I',read(0x17d28610+0x30,4))[0]
result={'firmware_sha256':sha,'decoded_instructions':len(ins),'advertised_protocols':[hex(x) for x in read(0x17d28658,6)],'vendor_dispatcher':'0x17d06808','registration_helper':'0xd82e75d4','registration_table':{'va':'0x17d0d528','slots':9,'entry_format':'u32 protocol; u32 handler','initial_words':list(struct.unpack('<18I',read(0x17d0d528,72)))},'statically_resolved_registration_calls':calls,'registrations':regs,'registration_limit':'No additional direct JAL/c.JAL or adjacent AUIPC/JALR calls found. This is not a proof against arbitrary computed calls, later initialization, or runtime-loaded code. Protocol 0x83 is advertised, but its handler remains unidentified.','memlat_dispatch_table':'0x17d28288','memlat_commands':memlat,'perf_callbacks':{'initial_table':'0x17d28610','runtime_table':'0x17d0d4dc','fastchannel_table_offset':'0x30','fastchannel_callback':hex(fc),'fastchannel_callback_instructions':[f'{a:08x} {m} {o}' for a,s,m,o in ins if fc<=a<fc+4]},'domain_flags':'0x4c0c0000','limits_supported_bit31':bool(0x4c0c0000&(1<<31)),'fastchannels_advertised_bit27':bool(0x4c0c0000&(1<<27))}
ranges=[('vendor outer dispatch',0x17d06210,0x17d06258),('vendor dispatcher and response helpers',0x17d06808,0x17d06906),('registration helper',0xd82e75d4,0xd82e760c),('PMU handler and registration',0xd82e5736,0xd82e588e),('memlat registration',0xd82e6862,0xd82e6890),('memlat group/monitor lookup',0xd82e6022,0xd82e60fe),('memlat minimum and maximum',0xd82e65f4,0xd82e66d4),('memlat apply arbitration',0x17d2372a,0x17d2379c),('memlat apply output branches',0x17d23872,0x17d239a0),('stats handler and registration',0xd82e714a,0xd82e71b0),('stats buffer callback',0x17d07712,0x17d07726),('perf domain flags',0x17d06322,0x17d06354),('perf fastchannel command',0x17d064d4,0x17d0652a),('perf fastchannel callback',0x17d20dc6,0x17d20dca)]
text=[]
for label,start,end in ranges:
 text.append(f'[{label}: {start:#x}..{end:#x})')
 text.extend(f'{a:08x} {m} {o}' for a,s,m,o in ins if start<=a<end)
 text.append('')
args.outdir.mkdir(exist_ok=True,parents=True)
(args.outdir/'vendor-analysis.json').write_text(json.dumps(result,indent=2)+'\n')
(args.outdir/'vendor-excerpts.txt').write_text('\n'.join(text))
print(json.dumps({'firmware_sha256':sha,'registration_calls':len(calls),'excerpt_lines':len(text),'outdir':str(args.outdir)}))
