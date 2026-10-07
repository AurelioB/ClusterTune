#!/usr/bin/env python3
"""Derive import ABI from real headers; compare, never substitute kernel CRCs."""
import pathlib,re,struct,json,hashlib,sys
base=pathlib.Path(sys.argv[1]);raw=(base/'stock-kernel-image.bin').read_bytes();assert hashlib.sha256(raw).hexdigest()=='b53c6063332f1d7057416cb708e443d008634752ec578c56d68040b2af9bcdf3';symbols={x.split()[2]:int(x.split()[0],16) for x in (base/'stock-kernel-symbols.txt').read_text().splitlines() if len(x.split())==3};native=symbols['_text']
chosen={}
for unit in ['header_abi','module_abi','address_abi','base_abi']:
 for name,crc in re.findall(r'__crc_(\S+) = (0x[0-9a-f]+);',(base/'abi'/(unit+'.o.symversions')).read_text()):chosen[name]={'crc':int(crc,16),'unit':unit}
records=[];lines=[]
for name,entry in chosen.items():
 addr=symbols['__ksymtab_'+name];group='_gpl' if symbols['__start___ksymtab_gpl']<=addr<symbols['__stop___ksymtab_gpl'] else ''
 index=(addr-symbols['__start___ksymtab'+group])//12;crcaddr=symbols['__start___kcrctab'+group]+index*4
 kernelcrc=struct.unpack_from('<I',raw,crcaddr-native)[0]
 no=struct.unpack_from('<i',raw,addr-native+4)[0];start=addr+4+no-native;actualname=raw[start:start+128].split(b'\0')[0].decode();assert actualname==name
 record={'symbol':name,'generated_crc':hex(entry['crc']),'kernel_crc':hex(kernelcrc),'match':entry['crc']==kernelcrc,'derivation_unit':entry['unit']};records.append(record)
 lines.append(f"0x{entry['crc']:08x}\t{name}\tvmlinux\tEXPORT_SYMBOL{'_GPL' if group else ''}\t\n")
(base/'generated-abi-comparison.json').write_text(json.dumps(records,indent=2)+'\n')
assert all(x['match'] for x in records),'Refusing mismatched generated ABI'
(base/'build/Module.symvers').write_text(''.join(lines))
print(json.dumps({'genuinely_derived_abi_entries':len(records),'all_match':True}))
