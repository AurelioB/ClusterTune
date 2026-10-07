// SPDX-License-Identifier: GPL-2.0-only
#include <linux/device.h>
#include <linux/fwnode.h>
#include <linux/io.h>
#include <linux/ioport.h>
#include <linux/logic_pio.h>
#include <linux/module.h>
#include <linux/of_address.h>
#include <linux/pci.h>
#include <linux/pci_regs.h>
#include <linux/sizes.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/dma-direct.h> /* for bus_dma_region */
EXPORT_SYMBOL(of_address_to_resource);
