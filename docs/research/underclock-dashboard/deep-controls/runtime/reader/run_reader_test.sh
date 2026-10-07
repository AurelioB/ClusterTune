#!/system/bin/sh
set -eu
probe_dir=/data/local/tmp/ct-srb-reader-test
expected_sha=be20860cfbb1b3a82b5653823068625062d4701de109b730232caf6a4e7df55f
actual_sha=$(sha256sum "$probe_dir/ct_srb_reader.ko")
case "$actual_sha" in "$expected_sha "*) ;; *) echo 'FAIL binary hash'; exit 1;; esac
if [ -d /sys/module/ct_srb_reader ]; then echo 'FAIL helper already loaded'; exit 1; fi
cleanup() {
 if [ -d /sys/module/ct_srb_reader ]; then rmmod ct_srb_reader; fi
}
trap cleanup EXIT HUP INT TERM
snapshot() {
 echo "SNAPSHOT $1"
 cat /proc/sys/kernel/random/boot_id /proc/sys/kernel/tainted /sys/fs/selinux/enforce
 cat /proc/self/attr/current
 for policy in 0 3 7; do
  echo "POLICY $policy"
  for name in scaling_min_freq scaling_max_freq scaling_governor; do
   echo "$name $(cat /sys/devices/system/cpu/cpufreq/policy$policy/$name)"
  done
 done
 cat /sys/kernel/qcom-cpufreq-hw/print_cpufreq_debug_regs
}
snapshot before
for cycle in 1 2 3; do
 echo "CYCLE $cycle LOAD"
 insmod "$probe_dir/ct_srb_reader.ko"
 [ -d /sys/module/ct_srb_reader ]
 grep '^ct_srb_reader ' /proc/modules
 cat /sys/module/ct_srb_reader/taint
 echo "CYCLE $cycle UNLOAD"
 rmmod ct_srb_reader
 [ ! -d /sys/module/ct_srb_reader ]
 echo "CYCLE $cycle PASS"
done
snapshot after
dmesg | grep ct_srb_reader
echo 'PASS three load/read/unload cycles through PServer'
