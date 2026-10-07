// SPDX-License-Identifier: GPL-2.0-only
/* Fixed prime-domain experiment. All restoration executes before init returns. */
#include <linux/module.h>
#include <linux/io.h>
#include <linux/of_address.h>
#include <linux/delay.h>
#include <linux/timekeeping.h>
#ifndef CT_CLEAR_BIT
#define CT_CLEAR_BIT 0
#endif
#ifndef CT_HOLD_MS
#define CT_HOLD_MS 50
#endif
#if CT_HOLD_MS < 1 || CT_HOLD_MS > 3000
#error "Pulse duration outside bounded experiment range"
#endif
static int __init ct_srb_pulse_init(void)
{
 static const resource_size_t expected[]={0x17d91000,0x17d92000,0x17d93000};
 struct device_node *np;
 struct resource res[3],extra;
 void __iomem *reg;
 u32 before,target,observed,restore_input,restored;
 int i,ret;
 np=of_find_compatible_node(NULL,NULL,"qcom,cpufreq-epss");
 if(!np) return -ENODEV;
 for(i=0;i<3;i++) {
  ret=of_address_to_resource(np,i,&res[i]);
  if(ret) goto put;
  if(res[i].start!=expected[i] || resource_size(&res[i])!=0x1000 || !(res[i].flags&IORESOURCE_MEM)) {ret=-EINVAL;goto put;}
 }
 if(!of_address_to_resource(np,3,&extra)) {ret=-EINVAL;goto put;}
 reg=ioremap(res[2].start+0xbc,sizeof(u32));
 if(!reg) {ret=-ENOMEM;goto put;}
 before=readl(reg);
 if(before!=1) {pr_err("ct_srb_pulse: guard rejected value=0x%x; no writes\n",before);ret=-EBUSY;goto unmap;}
 target=CT_CLEAR_BIT ? (before&~1U) : before;
 pr_info("ct_srb_pulse: stage=write_begin mono_ns=%llu before=0x%x target=0x%x hold_ms=%u\n",ktime_get_ns(),before,target,(unsigned)CT_HOLD_MS);
 writel(target,reg);
 observed=readl(reg);
 pr_info("ct_srb_pulse: stage=write_confirmed mono_ns=%llu observed=0x%x\n",ktime_get_ns(),observed);
 if(observed==target) msleep(CT_HOLD_MS);
 restore_input=readl(reg);
 pr_info("ct_srb_pulse: stage=restore_begin mono_ns=%llu restore_input=0x%x\n",ktime_get_ns(),restore_input);
 /* Restore only the one bit touched, preserving any unrelated restore_input bits. */
 if(!(restore_input&1U)) writel(restore_input|1U,reg);
 restored=readl(reg);
 pr_info("ct_srb_pulse: stage=restore_confirmed mono_ns=%llu restored=0x%x\n",ktime_get_ns(),restored);
 ret=(observed==target && restored==before && restore_input==target) ? 0 : -EIO;
unmap:
 iounmap(reg);
put:
 of_node_put(np);
 return ret;
}
static void __exit ct_srb_pulse_exit(void) {pr_info("ct_srb_pulse: unloaded; register already restored\n");}
module_init(ct_srb_pulse_init);
module_exit(ct_srb_pulse_exit);
MODULE_LICENSE("GPL v2");
MODULE_DESCRIPTION("Bounded fixed-prime EPSS bit0 pulse with synchronous restoration");
