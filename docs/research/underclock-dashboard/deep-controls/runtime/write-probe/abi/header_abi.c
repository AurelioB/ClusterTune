// SPDX-License-Identifier: GPL-2.0-only
/* Host-only ABI derivation from actual running kernel headers. Never loaded.
 * genksyms computes declaration/type CRCs; none are copied from kernel exports.
 */
#include <linux/module.h>
#include <linux/io.h>
#include <linux/of_address.h>
#include <linux/tracepoint.h>
#include <linux/string.h>
#include <linux/delay.h>
#include <linux/timekeeping.h>

void module_layout(struct module *mod, struct modversion_info *ver,
 struct kernel_param *kp, struct kernel_symbol *ks, struct tracepoint *const *tp) {}
EXPORT_SYMBOL(module_layout);
/* Definitions from public kernel/panic.c and kernel/cfi.c. */
__visible noinstr void __stack_chk_fail(void) {}
EXPORT_SYMBOL(__stack_chk_fail);
void __ubsan_handle_cfi_check_fail_abort(void *data, void *ptr, void *vtable) {}
EXPORT_SYMBOL(__ubsan_handle_cfi_check_fail_abort);
EXPORT_SYMBOL(_printk);
EXPORT_SYMBOL(memset);
EXPORT_SYMBOL(__ioremap);
EXPORT_SYMBOL(arm64_use_ng_mappings);
EXPORT_SYMBOL(iounmap);
EXPORT_SYMBOL(of_find_compatible_node);
EXPORT_SYMBOL(of_address_to_resource);
EXPORT_SYMBOL(log_read_mmio);
EXPORT_SYMBOL(log_post_read_mmio);
EXPORT_SYMBOL(log_write_mmio);
EXPORT_SYMBOL(log_post_write_mmio);
EXPORT_SYMBOL(msleep);
EXPORT_SYMBOL(ktime_get);
MODULE_LICENSE("GPL v2");
