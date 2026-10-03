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
                while (!host.lifecycle.isStopping()) {
                    long now = SystemClock.elapsedRealtime();
                    if (host.lifecycle.initialHandoffExpired(host.lease != null, now, leaseDeadline)) {
                        host.lifecycle.finishStopping();
                        break;
                    }
                    long remaining = leaseDeadline - now;
                    boolean awaitingInitialLease = !host.lifecycle.hasEstablishedLease() && host.lease == null;
                    try {
                        host.wait(awaitingInitialLease ? Math.max(1L, remaining) : 0L);
                    } catch (InterruptedException interrupted) {
                        // Losing the main wait thread must not discard an Auto Tune checkpoint.
                        // Treat interrupts as wakeups and continue until a verified stop condition.
                        log("wait interrupted; continuing host lifecycle");
                    }
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

    /**
     * Pure lifecycle state used by the Binder host. The HostBinder monitor serializes access;
     * keeping the decisions here makes the no-lease startup timeout and post-lease recovery
     * independently testable without constructing Android Binder objects on the local JVM.
     */
    static final class HostProcessLifecycle {
        private boolean stopping;
        private boolean leaseEverEstablished;
        private boolean stopAfterLeaseLoss;

        boolean isStopping() {
            return stopping;
        }

        boolean hasEstablishedLease() {
            return leaseEverEstablished;
        }

        boolean initialHandoffExpired(boolean hasLease, long now, long deadline) {
            return !stopping && !leaseEverEstablished && !hasLease && now >= deadline;
        }

        void leaseEstablished() {
            leaseEverEstablished = true;
            stopAfterLeaseLoss = false;
        }

        void leaseLost() {
            if (leaseEverEstablished && !stopping) {
                stopAfterLeaseLoss = true;
            }
        }

        boolean isWaitingForLeaseLossRestoration(boolean hasLease) {
            return !stopping && stopAfterLeaseLoss && !hasLease;
        }

        boolean canFinishLeaseLoss(boolean hasLease, boolean restorationComplete) {
            return isWaitingForLeaseLossRestoration(hasLease) && restorationComplete;
        }

        void finishStopping() {
            stopping = true;
            stopAfterLeaseLoss = false;
        }
    }

    private static final class HostBinder extends Binder implements android.os.IInterface {
        final String name;
        final int owner;
        final long generation;
        final String method;
        final long epoch = System.nanoTime();
        final HostProcessLifecycle lifecycle = new HostProcessLifecycle();
        IBinder lease;
        IBinder.DeathRecipient leaseDeath;
        final RealHostFilesystem filesystem;
        final HostApplyEngine engine;
        final HostMinimumProtection minimumProtection;
        final ScheduledExecutorService watchdog;
        volatile HostAutoSessionController autoController;
        volatile HostTelemetrySessionController telemetryController;
        HostCapabilities capabilities;

        HostBinder(String name, int owner, long generation, String method) {
            this.name = name;
            this.owner = owner;
            this.generation = generation;
            this.method = method;
            this.filesystem = new RealHostFilesystem();
            this.engine = new HostApplyEngine(filesystem);
            this.minimumProtection = HostMinimumProtection.createIfSupported(filesystem, owner);
            this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ClusterTune-host-watchdog");
                thread.setDaemon(true);
                return thread;
            });
            this.watchdog.scheduleWithFixedDelay(this::watchdogTick, 1L, 1L, TimeUnit.SECONDS);
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
            if (minimumProtection != null) minimumProtection.recoverOrThrow(capabilities);
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

        private HostTelemetrySessionController telemetryController() {
            HostTelemetrySessionController current = telemetryController;
            if (current == null) {
                current = HostTelemetrySessionController.production(ensureCapabilities(), filesystem, epoch);
                telemetryController = current;
            }
            return current;
        }

        private boolean autoOwnsTelemetry() {
            HostAutoSessionController current = autoController;
            return current != null
                    && current.current().getStatus() == HostAutoSessionStatus.ACTIVE;
        }

        private HostTelemetrySessionSnapshot stopTelemetry(
                HostTelemetrySessionStatus status,
                String reason) {
            HostTelemetrySessionController current = telemetryController;
            return current == null ? null : current.stopCurrent(status, reason);
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

        private boolean restorationComplete(HostAutoSessionSnapshot snapshot) {
            return snapshot == null || snapshot.getRestorationComplete();
        }

        private boolean releaseProfileMinimums() {
            if (minimumProtection == null) return true;
            try {
                minimumProtection.releaseOrThrow(ensureCapabilities());
                return true;
            } catch (Throwable failure) {
                log("CPU minimum permission recovery pending: " + failure);
                return false;
            }
        }

        /** Caller holds this HostBinder's monitor. */
        private void finishStoppingLocked() {
            lifecycle.finishStopping();
            remove(name, this);
            notifyAll();
        }

        /**
         * A dead lease is an orphaned host, but it may still own frequency ceilings. Keep the
         * service registered until those ceilings are restored. Holding the host monitor around
         * the attempt prevents a replacement lease from starting a new session in the middle of
         * the old session's restoration.
         */
        private void onLeaseDied(IBinder candidate) {
            synchronized (this) {
                if (lease != candidate || lifecycle.isStopping()) return;
                lease = null;
                leaseDeath = null;
                lifecycle.leaseLost();
                try {
                    stopTelemetry(
                            HostTelemetrySessionStatus.STOPPED,
                            "privileged host lease ended");
                } catch (Throwable throwable) {
                    log("telemetry session cleanup failed after lease death: " + throwable);
                }
                try {
                    HostAutoSessionSnapshot stopped = stopAutoForExternalApply("privileged host lease ended");
                    if (lifecycle.canFinishLeaseLoss(false, restorationComplete(stopped) && releaseProfileMinimums())) {
                        finishStoppingLocked();
                    }
                } catch (Throwable throwable) {
                    log("automatic session restore failed after lease death: " + throwable);
                }
            }
        }

        private void watchdogTick() {
            synchronized (this) {
                if (lifecycle.isWaitingForLeaseLossRestoration(lease != null)) {
                    try {
                        stopTelemetry(
                                HostTelemetrySessionStatus.STOPPED,
                                "privileged host lease ended");
                    } catch (Throwable throwable) {
                        log("telemetry session watchdog cleanup failed after lease death: " + throwable);
                    }
                    try {
                        HostAutoSessionSnapshot stopped = stopAutoForExternalApply("privileged host lease ended");
                        if (lifecycle.canFinishLeaseLoss(lease != null, restorationComplete(stopped) && releaseProfileMinimums())) {
                            finishStoppingLocked();
                        }
                    } catch (Throwable throwable) {
                        log("automatic session watchdog restore failed after lease death: " + throwable);
                    }
                    return;
                }
            }

            HostAutoSessionController controller = autoController;
            if (controller != null) {
                try {
                    controller.expireIfNeeded();
                } catch (Throwable throwable) {
                    log("automatic session watchdog failed: " + throwable);
                }
            }
            HostTelemetrySessionController telemetry = telemetryController;
            if (telemetry != null) {
                try {
                    telemetry.expireIfNeeded();
                } catch (Throwable throwable) {
                    log("telemetry session watchdog failed: " + throwable);
                }
            }
        }

        private void closeBeforeExit() {
            try {
                stopTelemetry(
                        HostTelemetrySessionStatus.STOPPED,
                        "privileged host stopped");
            } catch (Throwable throwable) {
                log("telemetry session shutdown failed: " + throwable);
            }
            HostAutoSessionSnapshot stopped = null;
            Throwable stopFailure = null;
            boolean restored = false;
            int attempt = 0;
            // Never discard an authoritative in-memory checkpoint merely because a small fixed
            // retry budget was exhausted. An unexpected main-loop exit keeps the process alive
            // and retries with a delay until restoration is verified.
            while (!restored) {
                attempt++;
                try {
                    stopped = stopAutoForExternalApply("privileged host lease ended");
                    stopFailure = null;
                    restored = restorationComplete(stopped) && releaseProfileMinimums();
                } catch (Throwable throwable) {
                    stopFailure = throwable;
                }
                if (!restored) {
                    if (attempt == 1 || attempt % 10 == 0) {
                        if (stopped != null) {
                            log("automatic session restore incomplete during host shutdown: " + stopped.getMessage());
                        } else if (stopFailure != null) {
                            log("automatic session shutdown failed: " + stopFailure);
                        }
                    }
                    SystemClock.sleep(1000L);
                }
            }
            watchdog.shutdownNow();
            if (minimumProtection != null) minimumProtection.close();
            IBinder currentLease;
            IBinder.DeathRecipient currentDeath;
            synchronized (this) {
                lifecycle.finishStopping();
                remove(name, this);
                currentLease = lease;
                currentDeath = leaseDeath;
                lease = null;
                leaseDeath = null;
            }
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
                    if (lifecycle.isStopping()) {
                        throw new IllegalStateException("privileged host is stopping");
                    }
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
                            if (minimumProtection != null) {
                                minimumProtection.applyOrThrow(engine, discovered, request);
                            } else {
                                HostProfileApplyDispatcher.applyOrThrow(engine, discovered, request);
                            }
                            header(reply, true);
                            writeStatePayload(reply, discovered);
                            return true;
                        }
                        case HostProtocol.STOP: {
                            stopTelemetry(
                                    HostTelemetrySessionStatus.STOPPED,
                                    "privileged host stopped");
                            HostAutoSessionSnapshot stopped = stopAutoForExternalApply("privileged host stopped");
                            requireCompleteRestoration(stopped);
                            if (!releaseProfileMinimums()) {
                                throw new HostApplyFailure(HostApplyPhase.ROLLBACK, true, false, false,
                                        "CPU minimum permission recovery is pending; privileged host retained", null);
                            }
                            finishStoppingLocked();
                            header(reply, true);
                            return true;
                        }
                        case HostProtocol.LEASE:
                            IBinder candidate = data.readStrongBinder();
                            if (candidate == null) throw new IllegalArgumentException("host lease missing");
                            if (lease == candidate && leaseDeath != null) {
                                lifecycle.leaseEstablished();
                                header(reply, true);
                                return true;
                            }
                            IBinder previousLease = lease;
                            IBinder.DeathRecipient previousDeath = leaseDeath;
                            IBinder.DeathRecipient recipient = () -> onLeaseDied(candidate);
                            // Link first so a dead replacement cannot displace a healthy lease.
                            candidate.linkToDeath(recipient, 0);
                            lease = candidate;
                            leaseDeath = recipient;
                            lifecycle.leaseEstablished();
                            if (previousLease != null && previousLease != candidate) {
                                stopTelemetry(
                                        HostTelemetrySessionStatus.STOPPED,
                                        "privileged host lease replaced");
                            }
                            if (previousLease != null && previousDeath != null) {
                                try { previousLease.unlinkToDeath(previousDeath, 0); } catch (Throwable ignored) { }
                            }
                            notifyAll();
                            header(reply, true);
                            return true;
                        case HostProtocol.READ_AUTO_CAPABILITIES:
                            writeAutoCapabilities(reply, autoController().capabilities());
                            return true;
                        case HostProtocol.START_AUTO_SESSION: {
                            String packageName = readBoundedString(data, HostProtocol.MAX_PACKAGE_LENGTH, "target package", false);
                            int targetFps = data.readInt();
                            long heartbeatTimeoutMs = data.readLong();
                            int hasBaseline = data.readInt();
                            if (hasBaseline != 0 && hasBaseline != 1) {
                                throw new IllegalArgumentException("invalid Auto Tune baseline flag");
                            }
                            ApplyRequest baseline = hasBaseline == 1
                                    ? readApplyRequest(data, ensureCapabilities())
                                    : null;
                            stopTelemetry(
                                    HostTelemetrySessionStatus.UNAVAILABLE,
                                    "performance telemetry is held by Auto Tune");
                            HostAutoSessionSnapshot snapshot = autoController().start(
                                    new AutoSessionRequest(packageName, targetFps, heartbeatTimeoutMs, baseline));
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
                        case HostProtocol.START_TELEMETRY_SESSION: {
                            String packageName = readBoundedString(
                                    data,
                                    HostProtocol.MAX_PACKAGE_LENGTH,
                                    "target package",
                                    true);
                            int targetFps = data.readInt();
                            long heartbeatTimeoutMs = data.readLong();
                            HostTelemetrySessionSnapshot snapshot = telemetryController().start(
                                    new TelemetrySessionRequest(packageName, targetFps, heartbeatTimeoutMs),
                                    !autoOwnsTelemetry());
                            writeTelemetrySnapshot(reply, snapshot);
                            return true;
                        }
                        case HostProtocol.READ_TELEMETRY_SESSION: {
                            String sessionId = readBoundedString(
                                    data,
                                    HostProtocol.MAX_SESSION_ID_LENGTH,
                                    "session ID",
                                    false);
                            long expectedEpoch = requireExpectedEpoch(data);
                            long afterSequence = data.readLong();
                            if (afterSequence < -1L) {
                                throw new IllegalArgumentException("invalid telemetry sequence");
                            }
                            writeTelemetrySnapshot(
                                    reply,
                                    telemetryController().read(
                                            sessionId,
                                            expectedEpoch,
                                            afterSequence,
                                            !autoOwnsTelemetry()));
                            return true;
                        }
                        case HostProtocol.STOP_TELEMETRY_SESSION: {
                            String sessionId = readBoundedString(
                                    data,
                                    HostProtocol.MAX_SESSION_ID_LENGTH,
                                    "session ID",
                                    false);
                            long expectedEpoch = requireExpectedEpoch(data);
                            writeTelemetrySnapshot(
                                    reply,
                                    telemetryController().stop(sessionId, expectedEpoch));
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
            File frequenciesPath = new File(kgsl, "gpu_available_frequencies");
            List<Long> rawFrequencies = readRawFreqs(frequenciesPath);
            ArrayList<Long> frequencies = new ArrayList<>();
            add(frequencies, rawFrequencies);
            long current = readLong(maxPath);
            long stable = Math.max(current, max(frequencies, current));
            File minPath = new File(kgsl, "min_gpuclk");
            long observedMin = minPath.isFile()
                    ? readLong(minPath)
                    : kgslObservedMin(
                            rawFrequencies,
                            readLong(new File(kgsl, "min_pwrlevel")));
            return new GpuDomain(
                    "kgsl-3d0",
                    minPath.isFile() ? minPath.getPath() : null,
                    maxPath.getPath(),
                    new File(kgsl, "gpuclk").getPath(),
                    frequencies,
                    stable,
                    stable,
                    observedMin,
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
            int maximumsOnlyValue = data.readInt();
            if (maximumsOnlyValue != 0 && maximumsOnlyValue != 1) {
                throw new IllegalArgumentException("invalid maximums-only flag");
            }
            boolean maximumsOnly = maximumsOnlyValue == 1;

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
            return new ApplyRequest(max, gpu, reset, ids, gpuId, gpuPath, stabilized > 0 ? stabilized : null, maximumsOnly);
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
                File gpuMaxPath = new File(gpu.getMaxPath());
                reply.writeLong(readLong(gpuMaxPath));
                boolean hasMinPath = gpu.getMinPath() != null;
                long liveMinimum = hasMinPath ? readLong(new File(gpu.getMinPath())) : -1L;
                File gpuDirectory = gpuMaxPath.getParentFile();
                boolean pathlessKgsl = !hasMinPath
                        && "max_gpuclk".equals(gpuMaxPath.getName())
                        && gpuDirectory != null
                        && gpuDirectory.getName().startsWith("kgsl-");
                List<Long> liveKgslFrequencies = java.util.Collections.emptyList();
                long liveKgslMinPowerLevel = -1L;
                if (pathlessKgsl) {
                    liveKgslFrequencies = readRawFreqs(
                            new File(gpuDirectory, "gpu_available_frequencies"));
                    liveKgslMinPowerLevel = readLong(new File(gpuDirectory, "min_pwrlevel"));
                }
                reply.writeLong(gpuSnapshotMin(
                        hasMinPath,
                        liveMinimum,
                        pathlessKgsl,
                        liveKgslFrequencies,
                        liveKgslMinPowerLevel));
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

        private void writeTelemetrySnapshot(Parcel reply, HostTelemetrySessionSnapshot value) {
            header(reply, true);
            reply.writeString(value.getSessionId());
            reply.writeLong(value.getHostEpoch());
            reply.writeInt(value.getStatus().ordinal());
            reply.writeInt(value.getTargetFps());
            HostAutoTelemetry telemetry = value.getTelemetry();
            reply.writeInt(telemetry == null ? 0 : 1);
            if (telemetry != null) writeAutoTelemetryPayload(reply, telemetry);
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

    static long kgslObservedMin(List<Long> frequencies, long minPowerLevel) {
        if (minPowerLevel < 0L || minPowerLevel >= frequencies.size()) {
            return -1L;
        }
        Long frequency = frequencies.get((int) minPowerLevel);
        return frequency != null && frequency > 0L ? frequency : -1L;
    }

    static long gpuSnapshotMin(
            boolean hasMinPath,
            long liveMinimum,
            boolean pathlessKgsl,
            List<Long> liveKgslFrequencies,
            long liveKgslMinPowerLevel) {
        if (hasMinPath) {
            return liveMinimum;
        }
        if (!pathlessKgsl) {
            return -1L;
        }
        return kgslObservedMin(liveKgslFrequencies, liveKgslMinPowerLevel);
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

    private static List<Long> readRawFreqs(File file) {
        return parseRawFrequencies(readText(file));
    }

    static List<Long> parseRawFrequencies(String text) {
        List<Long> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        try {
            for (String value : text.trim().split("\\s+")) {
                long frequency = Long.parseLong(value);
                if (frequency <= 0L) {
                    result.clear();
                    return result;
                }
                result.add(frequency);
            }
        } catch (Throwable ignored) {
            // A partial list cannot safely preserve power-level positions.
            result.clear();
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
