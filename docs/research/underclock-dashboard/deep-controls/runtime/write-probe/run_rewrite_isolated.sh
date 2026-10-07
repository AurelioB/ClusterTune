#!/system/bin/sh
set -eu
probe_dir=/data/local/tmp/ct-srb-pulse
marker="$probe_dir/write-isolated-stage.txt"
stage() {
 echo "$1"
 echo "$1" > "$marker"
 sync
}
[ ! -d /sys/module/ct_srb_pulse ]
case "$(sha256sum "$probe_dir/rewrite.ko")" in 'cc08ea184abb3d7d1566a312b9c715fd09c23b1f0c8e1f10fc612a4aa210bbd6 '*) ;; *) exit 1;; esac
trap 'if [ -d /sys/module/ct_srb_pulse ]; then rmmod ct_srb_pulse; fi; echo ct-srb-isolated > /sys/power/wake_unlock' EXIT
 echo 'ct-srb-isolated 20000000000' > /sys/power/wake_lock
cat /proc/sys/kernel/random/boot_id /proc/self/attr/current
stage before_same_value_insmod
insmod "$probe_dir/rewrite.ko"
stage after_same_value_insmod
rmmod ct_srb_pulse
stage after_same_value_unload
cat /proc/sys/kernel/random/boot_id
