// SPDX-License-Identifier: GPL-2.0-only
/* Temporary read-only probe: no parameters, callbacks, sysfs endpoint or writes. */
#include <linux/module.h>
#include <linux/io.h>
#include <linux/of_address.h>

static int __init ct_srb_reader_init(void)
{
 static const resource_size_t expected[] = {0x17d91000,0x17d92000,0x17d93000};
 struct device_node *np;
 struct resource resources[3], extra;
 void __iomem *mapping;
 u32 value;
 int i,ret;
 np=of_find_compatible_node(NULL,NULL,"qcom,cpufreq-epss");
 if(!np) return -ENODEV;
 /* Validate all three resources before attempting any MMIO access. */
 for(i=0;i<3;i++) {
  ret=of_address_to_resource(np,i,&resources[i]);
  if(ret) goto out;
  if(resources[i].start!=expected[i] || resource_size(&resources[i])!=0x1000 || !(resources[i].flags & IORESOURCE_MEM)) {ret=-EINVAL;goto out;}
 }
 if(!of_address_to_resource(np,3,&extra)) {ret=-EINVAL;goto out;}
 for(i=0;i<3;i++) {
  mapping=ioremap(resources[i].start+0xbc,sizeof(u32));
  if(!mapping) {ret=-ENOMEM;goto out;}
  value=readl_relaxed(mapping);
  iounmap(mapping);
  pr_info("ct_srb_reader: domain=%d base=0x%llx offset=0xbc value=0x%08x\n",i,(unsigned long long)resources[i].start,value);
 }
 ret=0;
 pr_info("ct_srb_reader: read-only probe complete; no mappings retained\n");
out:
 of_node_put(np);
 return ret;
}
static void __exit ct_srb_reader_exit(void) {pr_info("ct_srb_reader: unloaded\n");}
module_init(ct_srb_reader_init);
module_exit(ct_srb_reader_exit);
MODULE_LICENSE("GPL v2");
MODULE_DESCRIPTION("Temporary fixed-resource read-only EPSS SRB probe");
