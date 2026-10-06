#!/usr/bin/env python3
"""Bounded host-only evidence extraction. Never executes firmware or accesses a device."""
from pathlib import Path
import json,struct,hashlib,argparse,sys
sys.dont_write_bytecode=True
from host_elf import ELF
p=argparse.ArgumentParser(description=__doc__)
for name in ('cpucp','tz','xbl','output'):p.add_argument('--'+name,type=Path,required=True)
args=p.parse_args();out=args.output;out.mkdir(parents=True,exist_ok=True)
imgs={'CPUCP':ELF(args.cpucp),'TZ':ELF(args.tz),'XBL_ARM64':ELF(args.xbl)}
expected_hashes={'CPUCP': '19de4a8dd45eaa80ca2dd33d7a73661373805efd3970c994f2274e920b1d3808', 'TZ': '917e26b15114884cd54bc47fdb40ae43b63ee6281ce74f217d3990dcdfa1d206', 'XBL_ARM64': '1eaaddce7ea3c61c14da11949e53ece0189be1940ad4d3f2b9822f2827d11bfd'}
for name,img in imgs.items():
 assert hashlib.sha256(img.b).hexdigest()==expected_hashes[name], name+" image mismatch"
ranges={'CPUCP':[(0xd82e0968,0x7e),(0xd82e0d04,0x100),(0xd82e1074,0x3a),(0xd82e0222,0x11c),(0x17d0086e,0x7a),(0xd82e5d6a,0x3c),(0x17d24656,0x6a),(0x17d0045c,0x1c),(0xd82e01a2,0x80),(0x17d20776,0x18),(0x17d0316a,0x28),(0x17d03ab2,0x2c)],'TZ':[(0x1570f1dc,0x180)],'XBL_ARM64':[(0x14832c4c,0x4c),(0x1486f1a4,0x8c)]}
manifest=[]
for name,img in imgs.items():
 assert img.machine==(243 if name=='CPUCP' else 183)
 manifest.append({'image':name,'path':str(img.path),'machine':img.machine,'sha256':hashlib.sha256(img.b).hexdigest(),'ranges':[{'va':hex(a),'size':hex(n)} for a,n in ranges[name]]})
 (out/(name.lower()+'-excerpts.txt')).write_text('\n\n'.join(f'Range {a:08x}+{n:x}\n'+img.dis(a,n) for a,n in ranges[name])+'\n')
c,t,x=[imgs[z] for z in ['CPUCP','TZ','XBL_ARM64']]
data={'cpucp_domain_objects':[],'epss_0xbc_initialization':[{'domain':0,'name':'L3','bit0':0,'store':'0xd82e0e00'},{'domain':1,'name':'Silver','bit0':0,'store':'0xd82e108a'},{'domain':2,'name':'Gold','bit0':1,'store':'0xd82e10a8'},{'domain':3,'name':'Gold_Plus','bit0':1,'store':'0xd82e0de8'}],'ep88_file_initializer':{'va':'0x17d0c37c','values':list(struct.unpack('<4I',c.read(0x17d0c37c,16))),'note':'Static policy/file defaults, not measured or demonstrated sampled-register state.'},'cpucp_boot_strings':[{'va':hex(a),'string':t.text(a)} for a in [0x1578273b,0x1578274b]],'xbl_boot_error_filename':{'va':'0x148ad2c8','string':x.text(0x148ad2c8)},'xbl_boot_function_table':[]}
for i in range(4):
 a=0xd82f7a58+i*24;r=struct.unpack('<6I',c.read(a,24));data['cpucp_domain_objects'].append({'domain':i,'object_va':hex(a),'words':[hex(v) for v in r],'epss_base_offset_0x14':hex(r[5])})
for a in [0x148bf018,0x148bf020,0x148bf028]:data['xbl_boot_function_table'].append({'slot_va':hex(a),'function':hex(struct.unpack('<Q',x.read(a,8))[0])})
(out/'decoded-tables.json').write_text(json.dumps(data,indent=2)+'\n');(out/'image-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(data,indent=2))
