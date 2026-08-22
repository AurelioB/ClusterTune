# Attribution

The `jdwp-injector` module is vendored from:

  https://github.com/wuyr/jdwp-injector-for-android
  Copyright (c) wuyr — licensed under Apache License 2.0

It provides on-device adb-over-wireless-debugging + JDWP code injection.
ClusterTune uses it (via `com.aure.clustertune.jdwp.JdwpInjectionExecutionMethod`)
to run CPU-frequency scripts as uid=system on unrooted Odin 2 / 2 Mini
devices, by injecting into the debuggable system app GameAssistant.

Only the injected payload differs from upstream: instead of loading a dex
via run-as (which does not work for a system app), ClusterTune directly
invokes Runtime.getRuntime().exec("sh <script>") over JDWP.

The original Apache-2.0 license text is retained in LICENSE-jdwp-injector.
