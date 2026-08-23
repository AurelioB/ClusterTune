package com.aure.clustertune.root.host;

import android.annotation.SuppressLint;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.content.Intent;
import android.os.UserHandle;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** app_process entry point. It exposes only the typed ClusterTune protocol. */
public final class ClusterTuneHostEntry {
    private ClusterTuneHostEntry() {
    }

    // The privileged UID owns cross-user authority; FLAG_RECEIVER_FROM_SHELL is hidden from SDK.
    @SuppressLint({"MissingPermission", "WrongConstant"})
    public static void main(String[] args) throws Exception {
        log("entered args=" + args.length);
        if (args.length != 6) {
            throw new IllegalArgumentException("service, owner uid, generation, method, package and nonce required");
        }
        if (!isPrivilegedHostUid(android.os.Process.myUid())) {
            throw new SecurityException("root/system host required");
        }

        // app_process does not initialize the framework main thread by itself. Initialize a
        // system context so the privileged process can hand its Binder to the app receiver.
        android.os.Looper.prepare();
        log("looper prepared");
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object activity = systemMain.invoke(null);
        java.lang.reflect.Method systemContext = activityThread.getDeclaredMethod("getSystemContext");
        systemContext.setAccessible(true);
        android.content.Context context = (android.content.Context) systemContext.invoke(activity);
        log("runtime initialized");

        String name = args[0];
        int owner = Integer.parseInt(args[1]);
        long generation = Long.parseLong(args[2]);
        HostBinder host = new HostBinder(name, owner, generation, args[3]);
        log("binder constructed name=" + name);
        android.os.Bundle payload = new android.os.Bundle();
        payload.putBinder("host", host);
        Intent handoff = new Intent("com.aure.clustertune.HOST_HANDOFF")
                .setPackage(args[4])
                .addFlags(0x00400000)
                .putExtra("nonce", args[5])
                .putExtra("name", name)
                .putExtra("generation", generation)
                .putExtra("method", args[3])
                .putExtra("payload", payload);
        context.sendBroadcastAsUser(handoff, UserHandle.getUserHandleForUid(owner));
        log("broadcast handoff returned");

        try {
            synchronized (host) {
                log("wait entered");
                long leaseDeadline = SystemClock.elapsedRealtime() + 5000L;
                while (!host.stopping) {
                    if (host.lease == null && SystemClock.elapsedRealtime() >= leaseDeadline) {
                        host.stopping = true;
                        break;
                    }
                    long remaining = leaseDeadline - SystemClock.elapsedRealtime();
                    if (host.lease == null && remaining <= 0L) continue;
                    host.wait(host.lease == null ? remaining : 0L);
                }
            }
        } finally {
            host.closeBeforeExit();
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
        IBinder lease;
        IBinder.DeathRecipient leaseDeath;
        final RealHostFilesystem filesystem;
        final HostApplyEngine engine;
        final ScheduledExecutorService watchdog;
        volatile HostAutoSessionController autoController;
        HostCapabilities capabilities;

        HostBinder(String name, int owner, long generation, String method) {
            this.name = name;
            this.owner = owner;
            this.generation = generation;
            this.method = method;
            this.filesystem = new RealHostFilesystem();
            this.engine = new HostApplyEngine(filesystem);
            this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ClusterTune-auto-watchdog");
                thread.setDaemon(true);
                return thread;
            });
            this.watchdog.scheduleWithFixedDelay(() -> {
                HostAutoSessionController controller = autoController;
                if (controller != null) {
                    try {
                        controller.expireIfNeeded();
                    } catch (Throwable throwable) {
                        log("automatic session watchdog failed: " + throwable);
                    }
                }
            }, 1L, 1L, TimeUnit.SECONDS);
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

        private HostCapabilities ensureCapabilities() {
            capabilities = capabilities == null ? discover() : capabilities;
            return capabilities;
        }

        private HostAutoSessionController autoController() {
            HostAutoSessionController current = autoController;
            if (current == null) {
                current = HostAutoSessionController.production(ensureCapabilities(), filesystem, engine, epoch);
                autoController = current;
            }
            return current;
        }

        private HostAutoSessionSnapshot stopAutoForExternalApply(String reason) {
            HostAutoSessionController current = autoController;
            return current == null ? null : current.stopCurrent(reason);
        }

        private void requireCompleteRestoration(HostAutoSessionSnapshot snapshot) {
            if (snapshot != null && snapshot.getRestorationAttempted() && !snapshot.getRestorationComplete()) {
                throw new HostApplyFailure(
                        HostApplyPhase.ROLLBACK,
                        true,
                        false,
                        false,
                        snapshot.getMessage() == null ? "automatic session restoration failed" : snapshot.getMessage(),
                        null);
            }
        }

        private void closeBeforeExit() {
            watchdog.shutdownNow();
            try {
                HostAutoSessionSnapshot stopped = stopAutoForExternalApply("privileged host lease ended");
                if (stopped != null && !stopped.getRestorationComplete()) {
                    log("automatic session restore incomplete during host shutdown: " + stopped.getMessage());
                }
            } catch (Throwable throwable) {
                log("automatic session shutdown failed: " + throwable);
            }
            IBinder currentLease = lease;
            IBinder.DeathRecipient currentDeath = leaseDeath;
            lease = null;
            leaseDeath = null;
            if (currentLease != null && currentDeath != null) {
                try { currentLease.unlinkToDeath(currentDeath, 0); } catch (Throwable ignored) { }
            }
        }

        private String readBoundedString(Parcel data, int maximumLength, String label, boolean nullable) {
            String value = data.readString();
            if (value == null) {
                if (nullable) return null;
                throw new IllegalArgumentException(label + " is missing");
            }
            if (value.length() > maximumLength || value.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException(label + " is invalid");
            }
            return value;
        }

        private Long readExpectedEpoch(Parcel data) {
            long value = data.readLong();
            return value == Long.MIN_VALUE ? null : value;
        }

        private long requireExpectedEpoch(Parcel data) {
            Long value = readExpectedEpoch(data);
            if (value == null) throw new IllegalArgumentException("host epoch is missing");
            return value;
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
                            writeCapabilities(reply, ensureCapabilities());
                            return true;
                        case HostProtocol.READ_STATE:
                            writeState(reply, ensureCapabilities());
                            return true;
                        case HostProtocol.READ_SNAPSHOT:
                            capabilities = ensureCapabilities();
                            header(reply, true);
                            reply.writeLong(epoch);
                            writeCapabilitiesPayload(reply, capabilities);
                            writeStatePayload(reply, capabilities);
                            return true;
                        case HostProtocol.APPLY_PROFILE: {
                            HostCapabilities discovered = ensureCapabilities();
                            ApplyRequest request = readApplyRequest(data, discovered);
                            HostAutoSessionSnapshot stopped = stopAutoForExternalApply("automatic session preempted by profile apply");
                            requireCompleteRestoration(stopped);
                            engine.applyOrThrow(discovered, request);
                            header(reply, true);
                            writeStatePayload(reply, discovered);
                            return true;
                        }
                        case HostProtocol.STOP: {
                            HostAutoSessionSnapshot stopped = stopAutoForExternalApply("privileged host stopped");
                            stopping = true;
                            remove(name, this);
                            notifyAll();
                            requireCompleteRestoration(stopped);
                            header(reply, true);
                            return true;
                        }
                        case HostProtocol.LEASE:
                            IBinder candidate = data.readStrongBinder();
                            if (candidate == null) throw new IllegalArgumentException("host lease missing");
                            if (lease == candidate && leaseDeath != null) {
                                header(reply, true);
                                return true;
                            }
                            IBinder previousLease = lease;
                            IBinder.DeathRecipient previousDeath = leaseDeath;
                            if (previousLease != null && previousDeath != null) {
                                try { previousLease.unlinkToDeath(previousDeath, 0); } catch (Throwable ignored) { }
                            }
                            lease = candidate;
                            IBinder.DeathRecipient recipient = () -> {
                                synchronized (this) {
                                    if (lease == candidate) {
                                        stopping = true;
                                        notifyAll();
                                    }
                                }
                            };
                            leaseDeath = recipient;
                            candidate.linkToDeath(recipient, 0);
                            header(reply, true);
                            return true;
                        case HostProtocol.READ_AUTO_CAPABILITIES:
                            writeAutoCapabilities(reply, autoController().capabilities());
                            return true;
                        case HostProtocol.START_AUTO_SESSION: {
                            String packageName = readBoundedString(data, HostProtocol.MAX_PACKAGE_LENGTH, "target package", false);
                            int targetFps = data.readInt();
                            long heartbeatTimeoutMs = data.readLong();
                            HostAutoSessionSnapshot snapshot = autoController().start(
                                    new AutoSessionRequest(packageName, targetFps, heartbeatTimeoutMs));
                            writeAutoSnapshot(reply, snapshot);
                            return true;
                        }
                        case HostProtocol.READ_AUTO_TELEMETRY: {
                            String sessionId = readBoundedString(data, HostProtocol.MAX_SESSION_ID_LENGTH, "session ID", true);
                            Long expectedEpoch = readExpectedEpoch(data);
                            long afterSequence = data.readLong();
                            if (afterSequence < -1L) throw new IllegalArgumentException("invalid telemetry sequence");
                            writeAutoSnapshot(reply, autoController().readTelemetry(sessionId, expectedEpoch, afterSequence));
                            return true;
                        }
                        case HostProtocol.APPLY_AUTO_STEP: {
                            String sessionId = readBoundedString(data, HostProtocol.MAX_SESSION_ID_LENGTH, "session ID", false);
                            long expectedEpoch = requireExpectedEpoch(data);
                            HostCapabilities discovered = ensureCapabilities();
                            ApplyRequest request = readApplyRequest(data, discovered);
                            writeAutoSnapshot(reply, autoController().applyStep(sessionId, expectedEpoch, request));
                            return true;
                        }
                        case HostProtocol.HEARTBEAT_AUTO_SESSION: {
                            String sessionId = readBoundedString(data, HostProtocol.MAX_SESSION_ID_LENGTH, "session ID", false);
                            long expectedEpoch = requireExpectedEpoch(data);
                            writeAutoSnapshot(reply, autoController().heartbeat(sessionId, expectedEpoch));
                            return true;
                        }
                        case HostProtocol.STOP_AUTO_SESSION: {
                            String sessionId = readBoundedString(data, HostProtocol.MAX_SESSION_ID_LENGTH, "session ID", true);
                            Long expectedEpoch = readExpectedEpoch(data);
                            writeAutoSnapshot(reply, autoController().stop(sessionId, expectedEpoch));
                            return true;
                        }
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

        private ApplyRequest readApplyRequest(Parcel data, HostCapabilities discovered) {
            int count = data.readInt();
            if (count < 0 || count != discovered.getCpus().size()) {
                throw new IllegalArgumentException("CPU domain count mismatch");
            }

            ArrayList<Long> max = new ArrayList<>(count);
            ArrayList<String> ids = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String id = data.readString();
                if (id == null || id.length() > HostProtocol.MAX_METADATA_LENGTH) {
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
            if (gpuId != null && gpuId.length() > HostProtocol.MAX_METADATA_LENGTH) {
                throw new IllegalArgumentException("GPU identity is too long");
            }
            if (gpuPath != null && gpuPath.length() > HostProtocol.MAX_METADATA_LENGTH) {
                throw new IllegalArgumentException("GPU path is too long");
            }
            return new ApplyRequest(max, gpu, reset, ids, gpuId, gpuPath, stabilized > 0 ? stabilized : null);
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

        private void writeAutoCapabilities(Parcel reply, HostAutoCapabilities value) {
            header(reply, true);
            reply.writeInt(value.getFrameStats() ? 1 : 0);
            reply.writeInt(value.getCpuLoad() ? 1 : 0);
            reply.writeInt(value.getCpuClocks() ? 1 : 0);
            reply.writeInt(value.getGpuBusy() ? 1 : 0);
            reply.writeInt(value.getGpuClock() ? 1 : 0);
            reply.writeInt(value.getThermal() ? 1 : 0);
            reply.writeString(bounded(value.getFrameBackend()));
            reply.writeString(bounded(value.getUnsupportedReason()));
        }

        private void writeAutoSnapshot(Parcel reply, HostAutoSessionSnapshot value) {
            header(reply, true);
            reply.writeString(value.getSessionId());
            reply.writeLong(value.getHostEpoch());
            reply.writeInt(value.getStatus().ordinal());
            reply.writeInt(value.getTargetFps());
            HostAutoTelemetry telemetry = value.getTelemetry();
            reply.writeInt(telemetry == null ? 0 : 1);
            if (telemetry != null) writeAutoTelemetryPayload(reply, telemetry);
            HostState state = value.getState();
            reply.writeInt(state == null ? 0 : 1);
            if (state != null) writeHostStateValue(reply, state);
            reply.writeInt(value.getRestorationAttempted() ? 1 : 0);
            reply.writeInt(value.getRestorationComplete() ? 1 : 0);
            reply.writeString(bounded(value.getMessage()));
        }

        private void writeAutoTelemetryPayload(Parcel reply, HostAutoTelemetry value) {
            reply.writeLong(value.getSequence());
            reply.writeLong(value.getTimestampNanos());
            reply.writeString(bounded(value.getFrameBackend()));
            reply.writeInt(value.getFrameConfidencePermille());
            reply.writeString(bounded(value.getFrameLayer()));
            reply.writeInt(value.getFrameCount());
            writeOptionalInt(reply, value.getFpsMilli());
            writeOptionalLong(reply, value.getFrameTimeP95Nanos());
            writeOptionalInt(reply, value.getSlowFrameRatioPermille());
            reply.writeInt(value.getFrameStale() ? 1 : 0);
            writeOptionalIntList(reply, value.getCpuLoadPermille(), 64);
            writeOptionalLongList(reply, value.getCpuClockKHz(), 64);
            writeOptionalInt(reply, value.getGpuBusyPermille());
            writeOptionalLong(reply, value.getGpuClockHz());
            List<HostThermalReading> thermal = value.getThermal();
            int thermalCount = Math.min(thermal.size(), HostProtocol.MAX_THERMAL_READINGS);
            reply.writeInt(thermalCount);
            for (int index = 0; index < thermalCount; index++) {
                HostThermalReading reading = thermal.get(index);
                reply.writeString(bounded(reading.getType()));
                reply.writeLong(reading.getTemperatureMilliCelsius());
            }
            List<String> unsupported = value.getUnsupportedMetrics();
            int unsupportedCount = Math.min(unsupported.size(), HostProtocol.MAX_UNSUPPORTED_METRICS);
            reply.writeInt(unsupportedCount);
            for (int index = 0; index < unsupportedCount; index++) {
                reply.writeString(bounded(unsupported.get(index)));
            }
        }

        private void writeHostStateValue(Parcel reply, HostState value) {
            int count = Math.min(value.getCpuMax().size(), 64);
            reply.writeInt(count);
            for (int index = 0; index < count; index++) reply.writeLong(value.getCpuMax().get(index));
            for (int index = 0; index < count; index++) reply.writeLong(value.getCpuMin().get(index));
            for (int index = 0; index < count; index++) reply.writeLong(value.getCpuCurrent().get(index));
            reply.writeInt(value.getGpuMax() == null ? 0 : 1);
            if (value.getGpuMax() != null) {
                reply.writeLong(value.getGpuMax());
                reply.writeLong(value.getGpuMin() == null ? -1L : value.getGpuMin());
                reply.writeLong(value.getGpuCurrent() == null ? -1L : value.getGpuCurrent());
            }
        }

        private void writeOptionalInt(Parcel reply, Integer value) {
            reply.writeInt(value == null ? 0 : 1);
            if (value != null) reply.writeInt(value);
        }

        private void writeOptionalLong(Parcel reply, Long value) {
            reply.writeInt(value == null ? 0 : 1);
            if (value != null) reply.writeLong(value);
        }

        private void writeOptionalIntList(Parcel reply, List<Integer> values, int maximum) {
            int count = Math.min(values.size(), maximum);
            reply.writeInt(count);
            for (int index = 0; index < count; index++) writeOptionalInt(reply, values.get(index));
        }

        private void writeOptionalLongList(Parcel reply, List<Long> values, int maximum) {
            int count = Math.min(values.size(), maximum);
            reply.writeInt(count);
            for (int index = 0; index < count; index++) writeOptionalLong(reply, values.get(index));
        }

        private String bounded(String value) {
            return value == null ? null : value.substring(0, Math.min(value.length(), HostProtocol.MAX_METADATA_LENGTH));
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
