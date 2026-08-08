package com.aure.clustertune.root.host;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** app_process entry point. It exposes only the typed ClusterTune protocol. */
public final class ClusterTuneHostEntry {
    private ClusterTuneHostEntry() {
    }

    public static void main(String[] args) throws Exception {
        log("entered args=" + args.length);
        if (args.length != 4) {
            throw new IllegalArgumentException("service, owner uid, generation and method required");
        }
        if (!isPrivilegedHostUid(android.os.Process.myUid())) {
            throw new SecurityException("root/system host required");
        }

        // app_process does not initialize the framework main thread by itself. PServer hosts
        // need the system context initialized before ServiceManager.addService, otherwise the
        // registration can return successfully but never become visible to clients.
        android.os.Looper.prepare();
        log("looper prepared");
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object activity = systemMain.invoke(null);
        java.lang.reflect.Method systemContext = activityThread.getDeclaredMethod("getSystemContext");
        systemContext.setAccessible(true);
        systemContext.invoke(activity);
        log("runtime initialized");

        String name = args[0];
        int owner = Integer.parseInt(args[1]);
        long generation = Long.parseLong(args[2]);
        HostBinder host = new HostBinder(name, owner, generation, args[3]);
        log("binder constructed name=" + name);

        java.lang.reflect.Method add = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("addService", String.class, IBinder.class);
        add.setAccessible(true);
        add.invoke(null, name, host);
        log("addService returned");

        synchronized (host) {
            log("wait entered");
            while (!host.stopping) {
                host.wait();
            }
        }
        log("wait exited");
    }

    private static void log(String message) {
        String path = System.getenv("CT_HOST_LOG");
        if (path == null || path.isEmpty()) return;
        try (java.io.FileWriter writer = new java.io.FileWriter(path, true)) {
            writer.write(message + "\n");
            writer.flush();
        } catch (Throwable ignored) { }
    }

    static boolean isPrivilegedHostUid(int uid) {
        return uid == 0 || uid == 1000;
    }

    private static IBinder service(String name) {
        try {
            java.lang.reflect.Method get = Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String.class);
            get.setAccessible(true);
            return (IBinder) get.invoke(null, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void remove(String name, IBinder self) {
        if (service(name) != self) {
            return;
        }
        try {
            java.lang.reflect.Method remove = Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("removeService", String.class);
            remove.setAccessible(true);
            remove.invoke(null, name);
        } catch (Throwable ignored) {
            // ServiceManager.removeService is hidden and may not exist on every release.
        }
    }

    private static int policyIndex(File policy) {
        String name = policy.getName();
        int start = "policy".length();
        if (!name.startsWith("policy") || name.length() == start) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(name.substring(start));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static final class HostBinder extends Binder implements android.os.IInterface {
        final String name;
        final int owner;
        final long generation;
        final String method;
        final long epoch = System.nanoTime();
        boolean stopping;
        final HostApplyEngine engine;
        HostCapabilities capabilities;

        HostBinder(String name, int owner, long generation, String method) {
            this.name = name;
            this.owner = owner;
            this.generation = generation;
            this.method = method;
            this.engine = new HostApplyEngine(new RealHostFilesystem());
            attachInterface(this, HostProtocol.DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        private void header(Parcel reply, boolean ok) {
            reply.writeInt(HostProtocol.VERSION);
            reply.writeInt(ok ? 1 : 0);
        }

        private void error(Parcel reply, Throwable throwable) {
            reply.setDataSize(0);
            reply.setDataPosition(0);
            header(reply, false);
            reply.writeString(throwable.getClass().getName());
            reply.writeString(String.valueOf(throwable.getMessage()));
            if (throwable instanceof HostApplyFailure) {
                HostApplyFailure failure = (HostApplyFailure) throwable;
                reply.writeInt(failure.getPhase().ordinal());
                reply.writeInt(failure.getMutationStarted() ? 1 : 0);
                reply.writeInt(failure.getRollbackComplete() ? 1 : 0);
                reply.writeInt(failure.getIndeterminate() ? 1 : 0);
            }
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                if (Binder.getCallingUid() != owner) {
                    throw new SecurityException("caller uid is not owner");
                }
                data.enforceInterface(HostProtocol.DESCRIPTOR);
                int version = data.readInt();
                if (version != HostProtocol.VERSION) {
                    throw new IllegalArgumentException("protocol version");
                }

                synchronized (this) {
                    switch (code) {
                        case HostProtocol.PING:
                            header(reply, true);
                            reply.writeLong(generation);
                            reply.writeString(method);
                            reply.writeInt(android.os.Process.myUid());
                            return true;
                        case HostProtocol.HOST_IDENTITY:
                            header(reply, true);
                            reply.writeInt(android.os.Process.myUid());
                            reply.writeInt(owner);
                            return true;
                        case HostProtocol.READ_CAPABILITIES:
                            capabilities = capabilities == null ? discover() : capabilities;
                            writeCapabilities(reply, capabilities);
                            return true;
                        case HostProtocol.READ_STATE:
                            capabilities = capabilities == null ? discover() : capabilities;
                            writeState(reply, capabilities);
                            return true;
                        case HostProtocol.READ_SNAPSHOT:
                            capabilities = capabilities == null ? discover() : capabilities;
                            header(reply, true);
                            reply.writeLong(epoch);
                            writeCapabilitiesPayload(reply, capabilities);
                            writeStatePayload(reply, capabilities);
                            return true;
                        case HostProtocol.APPLY_PROFILE:
                            HostCapabilities applied = apply(data);
                            header(reply, true);
                            writeStatePayload(reply, applied);
                            return true;
                        case HostProtocol.STOP:
                            stopping = true;
                            remove(name, this);
                            header(reply, true);
                            notifyAll();
                            return true;
                        default:
                            throw new IllegalArgumentException("unknown request");
                    }
                }
            } catch (Throwable throwable) {
                error(reply, throwable);
                return true;
            }
        }

        private HostCapabilities discover() {
            ArrayList<CpuDomain> cpus = new ArrayList<>();
            File root = new File("/sys/devices/system/cpu/cpufreq");
            File[] dirs = root.listFiles((file, filename) -> filename.startsWith("policy"));
            if (dirs != null) {
                Arrays.sort(dirs, Comparator
                        .comparingInt(ClusterTuneHostEntry::policyIndex)
                        .thenComparing(File::getName));
            }
            if (dirs != null) {
                for (File policy : dirs) {
                    String minPath = new File(policy, "scaling_min_freq").getPath();
                    String maxPath = new File(policy, "scaling_max_freq").getPath();
                    if (!new File(minPath).isFile() || !new File(maxPath).isFile()) {
                        continue;
                    }
                    ArrayList<Long> candidates = new ArrayList<>();
                    add(candidates, readLong(new File(policy, "cpuinfo_min_freq")));
                    List<Long> supported = readFreqs(new File(policy, "scaling_available_frequencies"));
                    add(candidates, supported);
                    List<Long> timeState = readTimeState(new File(policy, "stats/time_in_state"));
                    add(candidates, timeState);
                    long current = readLong(new File(maxPath));
                    long hardware = readLong(new File(policy, "cpuinfo_max_freq"));
                    long stable = Math.max(hardware, max(supported, 0L));
                    stable = Math.max(stable, max(timeState, 0L));
                    stable = Math.max(stable, current);
                    // Advertised frequencies are the writable ceiling.  The
                    // hardware/time-in-state/current values may expose a
                    // hidden stock bin, but that bin must remain an observed
                    // ceiling rather than being offered as a selectable one.
                    long selectable = supported.isEmpty() ? stable : max(supported, 0L);
                    long observedMin = readLong(new File(minPath));
                    cpus.add(new CpuDomain(
                            policy.getName(),
                            minPath,
                            maxPath,
                            new File(policy, "scaling_cur_freq").getPath(),
                            candidates,
                            supported,
                            stable,
                            stable,
                            observedMin,
                            selectable,
                            current));
                }
            }

            GpuDomain gpu = discoverKgslGpu();
            if (gpu == null) {
                gpu = discoverDevfreqGpu();
            }
            if (cpus.isEmpty()) {
                throw new IllegalStateException("no CPU policies discovered");
            }
            return new HostCapabilities(cpus, gpu);
        }

        private GpuDomain discoverKgslGpu() {
            File kgsl = new File("/sys/class/kgsl/kgsl-3d0");
            File maxPath = new File(kgsl, "max_gpuclk");
            if (!maxPath.isFile()) {
                return null;
            }
            List<Long> frequencies = readFreqs(new File(kgsl, "gpu_available_frequencies"));
            long current = readLong(maxPath);
            long stable = Math.max(current, max(frequencies, current));
            File minPath = new File(kgsl, "min_gpuclk");
            return new GpuDomain(
                    "kgsl-3d0",
                    minPath.isFile() ? minPath.getPath() : null,
                    maxPath.getPath(),
                    new File(kgsl, "gpuclk").getPath(),
                    frequencies,
                    stable,
                    stable,
                    readLong(minPath),
                    frequencies.isEmpty() ? stable : max(frequencies, 0L),
                    current);
        }

        private GpuDomain discoverDevfreqGpu() {
            File[] entries = new File("/sys/class/devfreq").listFiles();
            if (entries == null) {
                return null;
            }
            Arrays.sort(entries, Comparator
                    .comparingInt((File entry) -> gpuCandidateRank(entry.getName().toLowerCase()))
                    .thenComparing(File::getName));
            for (File entry : entries) {
                String name = entry.getName().toLowerCase();
                if (!(name.contains("kgsl-3d") || name.contains("gpu") || name.contains("mali")
                        ) || name.contains("bus") || name.contains("bw") || name.contains("memlat")) {
                    continue;
                }
                File maxPath = new File(entry, "max_freq");
                if (!maxPath.isFile()) {
                    continue;
                }
                long current = readLong(maxPath);
                List<Long> frequencies = readFreqs(new File(entry, "available_frequencies"));
                long stable = Math.max(current, max(frequencies, current));
                File minPath = new File(entry, "min_freq");
                return new GpuDomain(
                        entry.getName(),
                        minPath.isFile() ? minPath.getPath() : null,
                        maxPath.getPath(),
                        new File(entry, "cur_freq").getPath(),
                        frequencies,
                        stable,
                        stable,
                        readLong(minPath),
                        frequencies.isEmpty() ? stable : max(frequencies, 0L),
                        current);
            }
            return null;
        }

        private int gpuCandidateRank(String name) {
            if (name.contains("kgsl-3d")) return 0;
            if (name.contains("mali")) return 1;
            if (name.equals("gpu")) return 2;
            return 3;
        }

        private HostCapabilities apply(Parcel data) {
            capabilities = capabilities == null ? discover() : capabilities;
            HostCapabilities discovered = capabilities;
            int count = data.readInt();
            if (count < 0 || count != discovered.getCpus().size()) {
                throw new IllegalArgumentException("CPU domain count mismatch");
            }

            ArrayList<Long> max = new ArrayList<>(count);
            ArrayList<String> ids = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String id = data.readString();
                if (id == null) {
                    throw new IllegalArgumentException("CPU domain id is missing");
                }
                ids.add(id);
                max.add(data.readLong());
            }

            int hasGpuValue = data.readInt();
            if (hasGpuValue != 0 && hasGpuValue != 1) {
                throw new IllegalArgumentException("invalid GPU target flag");
            }
            Long gpu = hasGpuValue == 1 ? data.readLong() : null;

            int resetValue = data.readInt();
            if (resetValue != 0 && resetValue != 1) {
                throw new IllegalArgumentException("invalid stock reset flag");
            }
            boolean reset = resetValue == 1;
            String gpuId = data.readString();
            String gpuPath = data.readString();
            long stabilized = data.readLong();

            for (int index = 0; index < count; index++) {
                if (!discovered.getCpus().get(index).getId().equals(ids.get(index))) {
                    throw new IllegalArgumentException("CPU domain order mismatch");
                }
            }
            engine.applyOrThrow(discovered, new ApplyRequest(max, gpu, reset, ids, gpuId, gpuPath, stabilized > 0 ? stabilized : null));
            return discovered;
        }

        private void writeCapabilities(Parcel reply, HostCapabilities value) {
            header(reply, true);
            writeCapabilitiesPayload(reply, value);
        }
        private void writeCapabilitiesPayload(Parcel reply, HostCapabilities value) {
            reply.writeInt(value.getCpus().size());
            for (CpuDomain cpu : value.getCpus()) {
                reply.writeString(cpu.getId());
                reply.writeString(cpu.getMinPath());
                reply.writeString(cpu.getMaxPath());
                reply.writeInt(cpu.getMinimumCandidates().size());
                for (Long frequency : cpu.getMinimumCandidates()) {
                    reply.writeLong(frequency);
                }
                reply.writeInt(cpu.getSupportedFrequencies().size());
                for (Long frequency : cpu.getSupportedFrequencies()) {
                    reply.writeLong(frequency);
                }
                reply.writeLong(cpu.getStockMax());
                reply.writeLong(cpu.getObservedMax());
                reply.writeLong(cpu.getObservedMin());
                reply.writeLong(cpu.getSelectableMax());
                reply.writeLong(cpu.getCurrentMax());
            }
            reply.writeInt(value.getGpu() == null ? 0 : 1);
            if (value.getGpu() != null) {
                GpuDomain gpu = value.getGpu();
                reply.writeString(gpu.getId());
                reply.writeString(gpu.getMinPath());
                reply.writeString(gpu.getMaxPath());
                reply.writeString(gpu.getCurPath());
                reply.writeInt(gpu.getSupportedFrequencies().size());
                for (Long frequency : gpu.getSupportedFrequencies()) {
                    reply.writeLong(frequency);
                }
                reply.writeLong(gpu.getStockMax());
                reply.writeLong(gpu.getObservedMax());
                reply.writeLong(gpu.getObservedMin());
                reply.writeLong(gpu.getSelectableMax());
                reply.writeLong(gpu.getCurrentMax());
            }
        }

        private void writeState(Parcel reply, HostCapabilities value) {
            header(reply, true);
            writeStatePayload(reply, value);
        }
        private void writeStatePayload(Parcel reply, HostCapabilities value) {
            reply.writeInt(value.getCpus().size());
            for (CpuDomain cpu : value.getCpus()) {
                reply.writeLong(readLong(new File(cpu.getMaxPath())));
            }
            for (CpuDomain cpu : value.getCpus()) {
                reply.writeLong(readLong(new File(cpu.getMinPath())));
            }
            for (CpuDomain cpu : value.getCpus()) {
                reply.writeLong(readLong(new File(cpu.getCurPath())));
            }
            reply.writeInt(value.getGpu() == null ? 0 : 1);
            if (value.getGpu() != null) {
                GpuDomain gpu = value.getGpu();
                reply.writeLong(readLong(new File(gpu.getMaxPath())));
                reply.writeLong(gpu.getMinPath() == null ? -1 : readLong(new File(gpu.getMinPath())));
                reply.writeLong(gpu.getCurPath() == null ? -1 : readLong(new File(gpu.getCurPath())));
            }
        }
    }

    private static long max(List<Long> values, long fallback) {
        long result = fallback;
        for (Long value : values) {
            if (value != null && value > result) {
                result = value;
            }
        }
        return result;
    }

    private static void add(List<Long> output, long value) {
        if (value > 0 && !output.contains(value)) {
            output.add(value);
        }
    }

    private static void add(List<Long> output, List<Long> values) {
        for (long value : values) {
            add(output, value);
        }
    }

    private static String readText(File file) {
        try {
            return new String(
                    java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long readLong(File file) {
        try {
            return Long.parseLong(readText(file));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static List<Long> readFreqs(File file) {
        List<Long> result = new ArrayList<>();
        try {
            for (String value : readText(file).split("\\s+")) {
                add(result, Long.parseLong(value));
            }
        } catch (Throwable ignored) {
            // Some kernels do not expose an available-frequency list.
        }
        return result;
    }

    private static List<Long> readTimeState(File file) {
        try {
            return HostDiscovery.INSTANCE.parseTimeInState(readText(file));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }
}
