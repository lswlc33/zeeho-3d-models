package io.github.lswlc33.zeehowebnext.cast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Socket;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 投屏前台服务：把 P2P 组网 → 三路通道 → H.264 编码串成一条会话。
 *
 * <p>线程模型（很重要，改之前先读）：
 * <ul>
 *   <li><b>主线程</b>：只做状态机、通知、事件回调（{@code mainHandler}）；</li>
 *   <li><b>网络线程池</b>（{@link #workers}）：每个被接受的 socket 一个任务，三路通道各自阻塞读；</li>
 *   <li><b>编码线程</b>：{@link CastEncoder} 自己的 drain 线程；</li>
 *   <li>组网回调来自系统 Binder/主线程，直接复用主线程状态机。</li>
 * </ul>
 * 因此这里的字段要么是 volatile，要么只在主线程读写；跨线程的计数器用 long 累加，
 * 读出来允许有瞬时误差（诊断用，不需要精确）。
 *
 * <p>前台服务类型：API 29+ 必须显式声明 {@code mediaProjection}（值 32），
 * 否则 Android 10+ 会直接抛 {@code MissingForegroundServiceTypeException}（Android 14 更严格）。
 */
public final class CastService extends Service implements
        CastP2pLink.Callback,
        CastControlChannel.Listener,
        CastMediaChannel.Host,
        CastFramePuller.Host,
        CastEncoder.Listener {

    private static final String TAG = "ZeehoCastService";

    /* ================= Intent / 通知常量 ================= */

    public static final String ACTION_START = "io.github.lswlc33.zeehowebnext.cast.action.START";
    public static final String ACTION_STOP = "io.github.lswlc33.zeehowebnext.cast.action.STOP";
    public static final String ACTION_PROJECTION_RESULT =
            "io.github.lswlc33.zeehowebnext.cast.action.PROJECTION_RESULT";
    public static final String EXTRA_OPTIONS = "options";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    /** 通知 id：与 Motexa 同号，方便在 dumpsys 里对照。 */
    public static final int NOTIFICATION_ID = 29041;
    public static final String CHANNEL_ID = "zeeho_cast_capture";
    public static final String CHANNEL_NAME = "投屏";
    /** ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION 的数值（API 29 常量，避免 compileSdk 依赖）。 */
    private static final int FGS_TYPE_MEDIA_PROJECTION = 32;

    /** 学到的“这台车能跑”的档位，按 P2P MAC 存应用私有存储（CAST.md §8.1）。 */
    public static final String PREFS_LEARNED = "zeeho_cast_learned";

    /** 诊断环形缓冲大小（与 JS 侧 200 条对齐）。 */
    public static final int DIAGNOSTIC_LIMIT = 200;
    /** 降档重试前的等待：给车机侧一点时间重建连接（r1.Q1 的 1000 ms）。 */
    public static final long RETRY_DELAY_MS = 1000L;
    /**
     * 拿到录屏授权后一直没 start 会话，就自己收掉服务。
     *
     * <p>为什么需要：Android 10+ 的前台服务 + 录屏授权是“可见的占用”，
     * 页面如果授权完就退出了，服务会一直挂着通知占着录屏。120 s 足够用户点“开始投屏”。
     */
    public static final long PROJECTION_IDLE_TIMEOUT_MS = 120000L;

    /* ================= 阶段（CAST.md §9） ================= */

    public static final String PHASE_IDLE = "idle";
    public static final String PHASE_PROVISIONING = "provisioning";
    public static final String PHASE_HANDSHAKING = "handshaking";
    public static final String PHASE_READY = "ready";
    public static final String PHASE_ADAPTING = "adapting";
    public static final String PHASE_CASTING = "casting";
    public static final String PHASE_STOPPING = "stopping";
    public static final String PHASE_FAILED = "failed";

    /** 阶段序号：允许向前跳（页面中途打开时 getStatus 可能直接是 casting），只拒绝回退。 */
    private static final String[] PHASE_ORDER = new String[]{
            PHASE_IDLE, PHASE_PROVISIONING, PHASE_HANDSHAKING, PHASE_READY, PHASE_ADAPTING,
            PHASE_CASTING
    };

    /** 事件出口（由 {@code ZeehoCastBridge} 注册，切到主线程后调用）。 */
    public interface EventListener {
        void onCastEvent(JSONObject event);
    }

    private static volatile EventListener eventListener;
    private static volatile CastService instance;
    private static final Object DIAGNOSTIC_LOCK = new Object();
    private static final ArrayDeque<JSONObject> DIAGNOSTICS = new ArrayDeque<JSONObject>();

    /** 注册事件出口；WebView 侧只需实现一个方法。 */
    public static void setEventListener(EventListener listener) {
        eventListener = listener;
    }

    /* ================= 会话状态 ================= */

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final AtomicLong sessionSerial = new AtomicLong();

    private volatile boolean sessionActive;
    private volatile long sessionId;
    private volatile String phase = PHASE_IDLE;
    private volatile String phaseReason = "";
    private volatile String captureSource = "screen";
    private volatile String frameFormat = "traditional";
    private volatile String tier = CastTiers.DEFAULT_TIER;
    private volatile int retries;
    private volatile boolean projectionReady;

    private CastProfile profile;
    private CastTiers.Caps caps = new CastTiers.Caps();
    private String chipset = CastTiers.CHIPSET_GENERIC;
    private volatile CastTiers.Plan plan;

    private volatile CastP2pLink p2p;
    private volatile CastControlChannel controlChannel;
    private volatile CastMediaChannel mediaChannel;
    private volatile CastFramePuller framePuller;
    private volatile CastEncoder encoder;
    private MediaProjection projection;
    private MediaProjectionManager projectionManager;

    private volatile long startedAt;
    private volatile long castingStartedAt;
    private volatile long attemptBaseSentFrames;
    private volatile long sentFramesTotal;
    private volatile long sentBytesTotal;
    private volatile long keyframesTotal;
    private volatile long emptyPullsTotal;
    private volatile long droppedFramesTotal;
    private volatile boolean projectionStopReported;
    /** 本次会话是否已经学过档位（一次会话只学一次）。 */
    private volatile boolean learnedThisSession;
    /** 上次学到 / 本次生效的档位（写进 status，页面可显示）。 */
    private volatile String learnedTier = "";
    /** 媒体结束是否正在处理：避免 close socket 引发的二次回调把我们带进递归降档。 */
    private final java.util.concurrent.atomic.AtomicBoolean endingMedia =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Runnable projectionIdleTask = new Runnable() {
        @Override
        public void run() {
            if (!sessionActive) {
                stopSession("projection-idle");
            }
        }
    };

    private final Runnable retryTask = new Runnable() {
        @Override
        public void run() {
            CastP2pLink link = p2p;
            if (!sessionActive || link == null) {
                return;
            }
            endingMedia.set(false);
            if (!link.reprobe()) {
                fail(CastErrors.VEHICLE_DISCONNECTED, "retry without group owner address");
            }
        }
    };

    /* ================= 生命周期 ================= */

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        ensureChannel();
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(projectionIdleTask);
        mainHandler.removeCallbacks(retryTask);
        teardown();
        releaseProjection();
        sessionActive = false;
        instance = null;
        try {
            workers.shutdownNow();
            workers.awaitTermination(800, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            // 系统重建（进程被杀）：不自动续投，避免无授权状态下占着前台服务
            return START_NOT_STICKY;
        }
        if (ACTION_PROJECTION_RESULT.equals(action)) {
            handleProjectionResult(intent);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopSession("user");
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            handleStart(intent);
            return START_NOT_STICKY;
        }
        Log.w(TAG, "unknown action: " + action);
        return START_NOT_STICKY;
    }

    /* ================= 静态入口（给桥/壳调用） ================= */

    /** 启动一次投屏会话；{@code optionsJson} 见 README 的桥契约。 */
    public static void startSession(Context context, String optionsJson) {
        Intent intent = new Intent(context, CastService.class).setAction(ACTION_START);
        intent.putExtra(EXTRA_OPTIONS, optionsJson);
        startCompat(context, intent);
    }

    /** 停止会话（幂等）。 */
    public static void stopSession(Context context) {
        Intent intent = new Intent(context, CastService.class).setAction(ACTION_STOP);
        startCompat(context, intent);
    }

    /** 把 Activity 的屏幕录制授权结果交给服务（Android 14 要求先有前台服务再采集）。 */
    public static void deliverProjectionResult(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, CastService.class).setAction(ACTION_PROJECTION_RESULT);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_RESULT_DATA, data);
        startCompat(context, intent);
    }

    private static void startCompat(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException error) {
            // 后台启动受限/被系统拒绝：记一条诊断，桥那边会从 status 里看到 projectionReady=false
            record("service-start-rejected", payload("message", String.valueOf(error)));
        }
    }

    /** 当前是否已有会话在跑（桥用来提前回 session_active）。 */
    public static boolean isSessionActive() {
        CastService current = instance;
        return current != null && current.sessionActive;
    }

    /** 屏幕录制授权是否已就绪（桥用来提前回 projection_permission_required）。 */
    public static boolean isProjectionReady() {
        CastService current = instance;
        return current != null && current.projectionReady && current.projection != null;
    }

    /* ================= 屏幕录制授权 ================= */

    private void handleProjectionResult(Intent intent) {
        startForegroundCompat(buildNotification("准备投屏"));
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        // 壳的 compileSdk 未必 >= 33：用老 API（结果 extra 就是 Intent），行为一致。
        Object resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        Intent data = resultData instanceof Intent ? (Intent) resultData : null;
        if (resultCode == 0 || data == null) {
            emitError(CastErrors.PROJECTION_PERMISSION_REQUIRED, "no consent result");
            return;
        }
        if (projectionManager == null) {
            emitError(CastErrors.PROJECTION_UNAVAILABLE, "no MediaProjectionManager");
            return;
        }
        try {
            releaseProjection();
            projection = projectionManager.getMediaProjection(resultCode, data);
            projectionStopReported = false;
            projectionReady = projection != null;
            if (!projectionReady) {
                emitError(CastErrors.PROJECTION_UNAVAILABLE, "getMediaProjection returned null");
                return;
            }
            record("projection-ready", new JSONObject());
            emitStatus();
            mainHandler.removeCallbacks(projectionIdleTask);
            mainHandler.postDelayed(projectionIdleTask, PROJECTION_IDLE_TIMEOUT_MS);
        } catch (RuntimeException error) {
            emitError(CastErrors.PROJECTION_UNAVAILABLE, "getMediaProjection: " + error);
        }
    }

    private void releaseProjection() {
        MediaProjection current = projection;
        projection = null;
        projectionReady = false;
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /* ================= 会话启动 ================= */

    private void handleStart(Intent intent) {
        if (sessionActive) {
            emitError(CastErrors.SESSION_ACTIVE, "session already running");
            return;
        }
        JSONObject options;
        try {
            String raw = intent.getStringExtra(EXTRA_OPTIONS);
            options = raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (Exception error) {
            emitError(CastErrors.BRIDGE_BAD_JSON, "options: " + error);
            return;
        }
        JSONObject profileJson = options.optJSONObject("profile");
        CastProfile parsed;
        try {
            if (profileJson == null) {
                throw new Exception("missing profile");
            }
            parsed = CastProfile.fromJson(profileJson);
        } catch (Exception error) {
            emitError(CastErrors.QR_INVALID, "profile: " + error);
            return;
        }
        String requestedSource = options.optString("captureSource", "screen");
        if (!"screen".equals(requestedSource)) {
            // 只实现了整屏采集；theme 渲染器不在本仓库范围（CAST.md 的 theme 是 Motexa 自有渲染）
            emitError(CastErrors.CAPTURE_SOURCE_UNSUPPORTED, "captureSource=" + requestedSource);
            return;
        }
        String requestedFormat = options.optString("frameFormat", "traditional");
        if (!"traditional".equals(requestedFormat) && !"extended".equals(requestedFormat)) {
            emitError(CastErrors.UNSUPPORTED_FRAME_FORMAT, "frameFormat=" + requestedFormat);
            return;
        }
        if (projection == null) {
            // 与 Motexa 一致：没有屏幕录制授权就不起传输（r1.p1 的 waiting-projection）
            setPhase(PHASE_FAILED, CastErrors.PROJECTION_PERMISSION_REQUIRED);
            emitError(CastErrors.PROJECTION_PERMISSION_REQUIRED, "call requestProjection first");
            return;
        }

        profile = parsed;
        captureSource = requestedSource;
        frameFormat = requestedFormat;
        String requestedTier = options.optString("tier", "");
        if (CastTiers.isKnownTier(requestedTier)) {
            tier = requestedTier;
        } else {
            // 页面没指定就吃“上次学到的档位”，避免每次从 performance 重新试错
            tier = loadLearnedTier(parsed.p2pMac);
        }
        learnedTier = tier;
        learnedThisSession = false;
        caps = CastTiers.Caps.fromJson(options);
        chipset = options.optString("chipset", CastTiers.detectChipset());
        retries = 0;
        plan = null;
        sentFramesTotal = 0;
        sentBytesTotal = 0;
        keyframesTotal = 0;
        emptyPullsTotal = 0;
        droppedFramesTotal = 0;
        attemptBaseSentFrames = 0;
        castingStartedAt = 0;
        startedAt = System.currentTimeMillis();

        mainHandler.removeCallbacks(projectionIdleTask);
        sessionId = sessionSerial.incrementAndGet();
        sessionActive = true;
        startForegroundCompat(buildNotification("正在连接车机"));
        record("session-start", profile.toMaskedJson());
        setPhase(PHASE_PROVISIONING, "");

        CastP2pLink link = new CastP2pLink(this, this, sessionSerial);
        p2p = link;
        link.start(profile, sessionId);
    }

    /* ================= 收尾 / 失败 ================= */

    private void stopSession(String reason) {
        boolean wasActive = sessionActive;
        sessionActive = false;
        if (wasActive) {
            setPhase(PHASE_STOPPING, reason);
        }
        sessionSerial.incrementAndGet();
        mainHandler.removeCallbacks(retryTask);
        teardown();
        releaseProjection();
        if (wasActive) {
            setPhase(PHASE_IDLE, "");
        } else {
            phase = PHASE_IDLE;
            phaseReason = "";
        }
        record("session-stop", payload("reason", reason));
        stopForegroundCompat();
        stopSelf();
    }

    /** 失败：先报错，再收尾（顺序反了页面会先看到 idle 再看到 error）。 */
    private void fail(String code, String detail) {
        if (!sessionActive) {
            return;
        }
        sessionActive = false;
        mainHandler.removeCallbacks(retryTask);
        setPhase(PHASE_FAILED, code);
        emitError(code, detail);
        sessionSerial.incrementAndGet();
        teardown();
        releaseProjection();
        stopForegroundCompat();
        stopSelf();
    }

    /** 关闭 socket → 停编码器；可重复调用。 */
    private void teardown() {
        CastP2pLink link = p2p;
        p2p = null;
        if (link != null) {
            try {
                link.stop();
            } catch (RuntimeException error) {
                Log.w(TAG, "p2p stop: " + error);
            }
        }
        CastEncoder currentEncoder = encoder;
        encoder = null;
        if (currentEncoder != null) {
            try {
                currentEncoder.stop();
            } catch (RuntimeException error) {
                Log.w(TAG, "encoder stop: " + error);
            }
        }
        controlChannel = null;
        mediaChannel = null;
        framePuller = null;
        plan = null;
        castingStartedAt = 0;
    }

    /* ================= 状态机（CAST.md §9） ================= */

    /**
     * 是否允许流转。
     *
     * <p>规则（与 {@code sdk/vehicle-cast.js} canCastTransition 一致）：
     * <ul>
     *   <li>{@code failed/stopping/idle} 从任何活动态都能进；</li>
     *   <li>{@code adapting} 只从 casting 进，出到 casting；</li>
     *   <li>其余按序号只进不退（页面中途打开时的向前跳是合法的）。</li>
     * </ul>
     */
    static boolean canTransition(String from, String to) {
        if (to == null || to.equals(from)) {
            return true;
        }
        if (PHASE_FAILED.equals(to) || PHASE_STOPPING.equals(to) || PHASE_IDLE.equals(to)) {
            return true;
        }
        if (PHASE_FAILED.equals(from)) {
            return PHASE_PROVISIONING.equals(to);
        }
        if (PHASE_ADAPTING.equals(to)) {
            return PHASE_CASTING.equals(from) || PHASE_ADAPTING.equals(from);
        }
        int fromRank = rankOf(from);
        int toRank = rankOf(to);
        if (fromRank < 0 || toRank < 0) {
            return false;
        }
        return toRank >= fromRank;
    }

    private static int rankOf(String phase) {
        for (int i = 0; i < PHASE_ORDER.length; i++) {
            if (PHASE_ORDER[i].equals(phase)) {
                return i;
            }
        }
        return -1;
    }

    private void setPhase(String next, String reason) {
        String current = phase;
        if (next == null || next.equals(current)) {
            phaseReason = reason == null ? "" : reason;
            emitStatus();
            return;
        }
        if (!canTransition(current, next)) {
            record("phase-rejected", payload("from", current, "to", next));
            return;
        }
        phase = next;
        phaseReason = reason == null ? "" : reason;
        if (PHASE_CASTING.equals(next)) {
            castingStartedAt = System.currentTimeMillis();
        }
        if (PHASE_FAILED.equals(next) || PHASE_IDLE.equals(next)) {
            castingStartedAt = 0;
        }
        record("phase", payload("from", current, "to", next, "reason", phaseReason));
        updateNotification();
        emitStatus();
    }

    /* ================= P2P 回调 ================= */

    @Override
    public void onPhase(String nextPhase, String reason) {
        String normalized = normalizePhase(nextPhase);
        if (normalized == null) {
            record("phase-ignored", payload("phase", String.valueOf(nextPhase)));
            return;
        }
        // 降档重试期间会重新跑 “探测 → 开监听”，但对外阶段应该一直是 adapting，
        // 否则页面状态机（只进不退）会把 handshaking/ready 当成非法回退丢掉。
        if (PHASE_ADAPTING.equals(phase)
                && (PHASE_PROVISIONING.equals(normalized) || PHASE_HANDSHAKING.equals(normalized)
                || PHASE_READY.equals(normalized))) {
            record("phase-suppressed", payload("to", normalized));
            return;
        }
        setPhase(normalized, reason);
    }

    /**
     * 把 Motexa 的阶段词映射成本仓词表（CAST.md §9）。
     *
     * <p>Motexa 有 {@code connecting / hotspot-monitor / waiting-projection / stopped}
     * 这些我们没有的阶段；直接透传会让页面状态机（只认 9 个词）拒绝整条事件。
     */
    static String normalizePhase(String raw) {
        if (raw == null) {
            return null;
        }
        if (PHASE_IDLE.equals(raw) || PHASE_PROVISIONING.equals(raw)
                || PHASE_HANDSHAKING.equals(raw) || PHASE_READY.equals(raw)
                || PHASE_CASTING.equals(raw) || PHASE_ADAPTING.equals(raw)
                || PHASE_STOPPING.equals(raw) || PHASE_FAILED.equals(raw)) {
            return raw;
        }
        if ("stopped".equals(raw)) {
            return PHASE_IDLE;
        }
        if ("connecting".equals(raw) || "hotspot-monitor".equals(raw)
                || "waiting-projection".equals(raw)) {
            return PHASE_PROVISIONING;
        }
        return null;
    }

    @Override
    public void onError(String code, String detail) {
        fail(code, detail);
    }

    @Override
    public void onProbed(String goAddress, long elapsedMs) {
        record("status-probe-ok", payload("host", goAddress, "elapsedMs", elapsedMs));
    }

    @Override
    public void onListenersReady() {
        record("listeners-ready", new JSONObject());
    }

    @Override
    public void onSocket(final int port, final Socket socket) {
        final long sid = sessionId;
        switch (port) {
            case CastWire.PORT_CONTROL: {
                final CastControlChannel channel = new CastControlChannel(this, this, sessionSerial);
                controlChannel = channel;
                submit(new Runnable() {
                    @Override
                    public void run() {
                        channel.serve(socket, sid);
                    }
                });
                return;
            }
            case CastWire.PORT_MEDIA: {
                final CastMediaChannel channel = new CastMediaChannel(this, sessionSerial);
                mediaChannel = channel;
                submit(new Runnable() {
                    @Override
                    public void run() {
                        channel.serve(socket, sid);
                    }
                });
                return;
            }
            case CastWire.PORT_DATA: {
                // 10920 只服务最新一条连接：新连接进来就关掉旧的（CAST.md §3.1）
                CastFramePuller previous = framePuller;
                if (previous != null) {
                    previous.closeQuietly("replaced");
                }
                final CastFramePuller puller = new CastFramePuller(this, sessionSerial);
                // 先把 extended 决定下来，避免第一帧就带错格式
                CastTiers.Plan current = plan;
                puller.setExtended("extended".equals(frameFormat) && current != null && current.extended);
                framePuller = puller;
                submit(new Runnable() {
                    @Override
                    public void run() {
                        puller.serve(socket, sid);
                    }
                });
                return;
            }
            default:
                Log.w(TAG, "unexpected port: " + port);
        }
    }

    @Override
    public void onLog(String event, JSONObject payload) {
        record(event, payload);
    }

    private void submit(Runnable task) {
        try {
            workers.execute(task);
        } catch (RejectedExecutionException error) {
            Log.w(TAG, "worker rejected: " + error);
        }
    }

    /* ================= 控制通道回调 ================= */

    @Override
    public void onStartSignal() {
        record("start-signal", new JSONObject());
    }

    @Override
    public void onClientInfoAnswered(boolean ok) {
        record("client-info-answered", payload("ok", ok));
    }

    @Override
    public void onSerialChecked(String sn, String clientSet) {
        record("serial-checked", payload("sn", CastProfile.maskSecret(sn, 4),
                "clientSet", clientSet));
    }

    @Override
    public void onFrame(String direction, int code, int bytes) {
        // 心跳等高频帧不进诊断环形缓冲，避免把有用的信息挤掉；只统计
        if (code != CastWire.CMD_HEARTBEAT_A && code != CastWire.CMD_HEARTBEAT_B
                && code != CastWire.CMD_HEARTBEAT_A_REPLY && code != CastWire.CMD_HEARTBEAT_B_REPLY
                && code != CastWire.CMD_PULL_FRAME) {
            record("frame", payload("direction", direction, "code", code, "bytes", bytes));
        }
    }

    @Override
    public void onControlClosed(String reason) {
        record("control-closed", payload("reason", reason));
    }

    /* ================= 媒体通道 Host ================= */

    @Override
    public CastTiers.Plan onConfigure(CastWire.CaptureConfig config) {
        CastTiers.Plan computed = CastTiers.plan(config, tier, chipset, caps);
        plan = computed;
        boolean extendedAck = wantsExtendedFrames() && config.extended;
        CastFramePuller puller = framePuller;
        if (puller != null) {
            puller.setExtended(extendedAck);
        }
        JSONObject details = computed.toJson();
        put(details, "requestedWidth", config.width);
        put(details, "requestedHeight", config.height);
        put(details, "requestedFps", config.fps);
        put(details, "requestedBitrate", config.bitrate);
        put(details, "extendedAck", extendedAck);
        record("media-capture-configured", details);
        return computed;
    }

    @Override
    public boolean onStartCasting() {
        // 用 currentPlan()：车机没发命令 16 就 112 时它会补一份兜底配置
        CastTiers.Plan current = currentPlan();
        if (current == null) {
            return false;
        }
        if (projection == null) {
            emitError(CastErrors.PROJECTION_PERMISSION_REQUIRED, "projection lost before start");
            return false;
        }
        CastEncoder created = new CastEncoder(this);
        if (!created.start(projection, current)) {
            return false;
        }
        encoder = created;
        attemptBaseSentFrames = sentFramesTotal;
        endingMedia.set(false);
        CastFramePuller puller = framePuller;
        if (puller != null) {
            puller.setCastingTimeout();
        }
        startForegroundCompat(buildNotification("投屏中 · " + current.width + "×" + current.height));
        setPhase(PHASE_CASTING, "");
        return true;
    }

    /**
     * 当前档位规划。
     *
     * <p>车机可能不发命令 16 就直接 112；按 r1.A0 的兜底配置补一份
     * {@code 1024x600 / fps 0 / encoder 2 / 2 MiB / extended false}，否则会因为“没配置”直接失败。
     */
    @Override
    public CastTiers.Plan currentPlan() {
        CastTiers.Plan current = plan;
        if (current != null) {
            return current;
        }
        CastWire.CaptureConfig fallback = new CastWire.CaptureConfig(1024, 600, 0,
                CastWire.CODEC_AVC, CastWire.DEFAULT_BITRATE, false);
        CastTiers.Plan computed = CastTiers.plan(fallback, tier, chipset, caps);
        plan = computed;
        record("media-config-fallback", computed.toJson());
        return computed;
    }

    @Override
    public boolean wantsExtendedFrames() {
        return "extended".equals(frameFormat);
    }

    @Override
    public void onMediaClosed(String reason) {
        record("media-closed", payload("reason", reason));
        if (!PHASE_CASTING.equals(phase)) {
            return;
        }
        handleMediaEnded(reason);
    }

    /* ================= 数据通道 Host ================= */

    @Override
    public boolean isCasting() {
        return PHASE_CASTING.equals(phase) && encoder != null;
    }

    @Override
    public CastEncoder.Frame pollFrame(long timeoutMs) {
        CastEncoder current = encoder;
        return current == null ? null : current.pollFrame(timeoutMs);
    }

    @Override
    public void requestKeyFrame() {
        CastEncoder current = encoder;
        if (current != null) {
            current.requestKeyFrame();
        }
    }

    @Override
    public void onFrameSent(int totalBytes, boolean keyframe) {
        sentFramesTotal++;
        sentBytesTotal += totalBytes;
        if (keyframe) {
            keyframesTotal++;
        }
        maybeLearnTier();
    }

    /**
     * 档位学习（p.b → o1）：跑满 15 s 且发过 ≥150 帧，就把当前档位记成这台车的首选。
     *
     * <p>只写本地偏好，不改变当前会话；下次 start 没指定 tier 时会用它。
     */
    private void maybeLearnTier() {
        if (learnedThisSession || castingStartedAt == 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - castingStartedAt;
        long sent = Math.max(0L, sentFramesTotal - attemptBaseSentFrames);
        if (!CastTiers.shouldLearnTier(elapsed, (int) sent, false)) {
            return;
        }
        learnedThisSession = true;
        learnedTier = tier;
        CastProfile current = profile;
        if (current != null) {
            saveLearnedTier(current.p2pMac, tier);
        }
        record("compatibility-learned", payload("compatibilityTier", tier,
                "elapsedMs", elapsed, "sentFrames", sent));
    }

    @Override
    public void onEmptyPull() {
        emptyPullsTotal++;
    }

    @Override
    public void onDataClosed(String reason) {
        record("data-closed", payload("reason", reason));
        if ("replaced".equals(reason)) {
            // 车机重连：旧连接被我们主动关掉，不代表会话结束
            return;
        }
        if (!PHASE_CASTING.equals(phase)) {
            return;
        }
        handleMediaEnded("data:" + reason);
    }

    /* ================= 媒体会话结束 / 降档（CAST.md §8） ================= */

    /**
     * 媒体通道断了：能降档就降档重试，否则失败。
     *
     * <p>门槛是“本次尝试跑满 60 s 且发了 ≥150 帧”（CAST.md §8、p.b）。
     * 刚连上就断多半是车机侧没在投屏页/用户切走，不该被当成解码不兼容。
     */
    private void handleMediaEnded(String reason) {
        if (!endingMedia.compareAndSet(false, true)) {
            // 已经在一个结束流程里（关 socket 会触发别的通道回调），不重复降档
            return;
        }
        long elapsed = castingStartedAt == 0 ? 0L
                : Math.max(0L, System.currentTimeMillis() - castingStartedAt);
        long sentThisAttempt = Math.max(0L, sentFramesTotal - attemptBaseSentFrames);
        CastP2pLink link = p2p;

        if (link != null && link.isRunning()
                && CastTiers.shouldDowngrade(tier, retries, elapsed, (int) sentThisAttempt, false)) {
            String next = CastTiers.nextTier(tier);
            if (next != null) {
                retries++;
                tier = next;
                CastEncoder currentEncoder = encoder;
                encoder = null;
                if (currentEncoder != null) {
                    currentEncoder.stop();
                }
                link.closeAcceptedSockets();
                plan = null;
                castingStartedAt = 0;
                setPhase(PHASE_ADAPTING, reason);
                record("compatibility-retry-scheduled",
                        payload("compatibilityTier", tier, "retryCount", retries, "reason", reason));
                mainHandler.removeCallbacks(retryTask);
                mainHandler.postDelayed(retryTask, RETRY_DELAY_MS);
                return;
            }
        }
        // 有帧却断、且已经没有重试空间 → 判定“降到底仍不兼容”；否则就是普通断开
        boolean canRetry = CastTiers.nextTier(tier) != null && retries < CastTiers.MAX_RETRIES;
        fail(sentThisAttempt > 0 && !canRetry
                        ? CastErrors.MEDIA_INCOMPATIBLE_AFTER_FALLBACK
                        : CastErrors.VEHICLE_DISCONNECTED,
                reason);
    }

    /* ================= 编码器回调 ================= */

    @Override
    public void onFormatChanged(int width, int height, String codecName, String bitrateMode) {
        record("encoder-format", payload("width", width, "height", height,
                "encoderName", codecName, "bitrateMode", bitrateMode));
    }

    @Override
    public void onFramesDropped(int dropped) {
        droppedFramesTotal += dropped;
    }

    @Override
    public void onFirstKeyFrame() {
        record("encoder-key-frame", new JSONObject());
    }

    @Override
    public void onFatal(String code, String detail) {
        fail(code, detail);
    }

    @Override
    public void onProjectionStopped() {
        if (projectionStopReported || !sessionActive) {
            return;
        }
        projectionStopReported = true;
        fail(CastErrors.PROJECTION_STOPPED, "media projection stopped");
    }

    /* ================= 状态 / 诊断（给桥） ================= */

    private String loadLearnedTier(String p2pMac) {
        if (p2pMac == null || p2pMac.length() == 0) {
            return CastTiers.DEFAULT_TIER;
        }
        SharedPreferences preferences =
                getSharedPreferences(PREFS_LEARNED, Context.MODE_PRIVATE);
        String stored = preferences.getString(p2pMac, "");
        return CastTiers.isKnownTier(stored) ? stored : CastTiers.DEFAULT_TIER;
    }

    private void saveLearnedTier(String p2pMac, String learned) {
        if (p2pMac == null || p2pMac.length() == 0 || !CastTiers.isKnownTier(learned)) {
            return;
        }
        try {
            getSharedPreferences(PREFS_LEARNED, Context.MODE_PRIVATE)
                    .edit().putString(p2pMac, learned).apply();
        } catch (RuntimeException error) {
            Log.w(TAG, "saveLearnedTier: " + error);
        }
    }

    /** 当前状态快照；服务没起来时返回 idle。字段名与 JS applyStatus 对齐。 */
    public static JSONObject statusSnapshot() {
        CastService current = instance;
        if (current == null) {
            JSONObject idle = new JSONObject();
            put(idle, "ok", true);
            put(idle, "phase", PHASE_IDLE);
            put(idle, "reason", "");
            put(idle, "casting", false);
            put(idle, "vehicleConnected", false);
            put(idle, "projectionReady", false);
            put(idle, "captureSource", "screen");
            put(idle, "frameFormat", "traditional");
            put(idle, "compatibilityTier", CastTiers.DEFAULT_TIER);
            put(idle, "compatibilityRetryCount", 0);
            put(idle, "stats", emptyStats());
            return idle;
        }
        return current.buildStatus();
    }

    private JSONObject buildStatus() {
        JSONObject status = new JSONObject();
        CastEncoder currentEncoder = encoder;
        CastP2pLink link = p2p;
        CastTiers.Plan currentPlan = plan;
        long elapsed = castingStartedAt == 0 ? 0L
                : Math.max(0L, System.currentTimeMillis() - castingStartedAt);
        long sentThisAttempt = Math.max(0L, sentFramesTotal - attemptBaseSentFrames);

        put(status, "ok", true);
        put(status, "phase", phase);
        put(status, "reason", phaseReason);
        put(status, "casting", PHASE_CASTING.equals(phase));
        put(status, "vehicleConnected", link != null && link.isRunning());
        put(status, "projectionReady", projectionReady);
        put(status, "captureSource", captureSource);
        put(status, "frameFormat", frameFormat);
        put(status, "compatibilityTier", tier);
        put(status, "compatibilityRetryCount", retries);
        put(status, "learnedCompatibilityTier", learnedTier);
        put(status, "sessionActive", sessionActive);
        put(status, "elapsedMs", elapsed);
        put(status, "p2p", payload("groupOwnerAddress",
                link == null || link.getGoAddress() == null ? "" : link.getGoAddress()));

        JSONObject stats = new JSONObject();
        put(stats, "sentFrames", sentFramesTotal);
        put(stats, "sentBytes", sentBytesTotal);
        put(stats, "keyframes", keyframesTotal);
        put(stats, "emptyPulls", emptyPullsTotal);
        put(stats, "droppedFrames", droppedFramesTotal);
        put(stats, "fps", estimateFps(elapsed, sentThisAttempt));
        put(status, "stats", stats);

        JSONObject encoderJson = new JSONObject();
        put(encoderJson, "running", currentEncoder != null && currentEncoder.isRunning());
        put(encoderJson, "encoderName", currentEncoder == null ? "" : currentEncoder.getCodecName());
        put(encoderJson, "bitrateMode", currentEncoder == null ? "" : currentEncoder.getBitrateMode());
        put(encoderJson, "queuedFrames", currentEncoder == null ? 0 : currentEncoder.getQueuedFrames());
        put(encoderJson, "parameterSetsReady",
                currentEncoder != null && currentEncoder.hasParameterSets());
        put(encoderJson, "framesProduced", currentEncoder == null ? 0 : currentEncoder.getFramesProduced());
        if (currentPlan != null) {
            put(encoderJson, "plan", currentPlan.toJson());
        }
        put(status, "encoder", encoderJson);
        return status;
    }

    private static JSONObject emptyStats() {
        JSONObject stats = new JSONObject();
        put(stats, "sentFrames", 0);
        put(stats, "sentBytes", 0);
        put(stats, "keyframes", 0);
        put(stats, "emptyPulls", 0);
        put(stats, "droppedFrames", 0);
        put(stats, "fps", 0);
        return stats;
    }

    /** 本次尝试的平均帧率（保留一位小数）；不做滑动窗口，诊断够用。 */
    private static double estimateFps(long elapsedMs, long frames) {
        if (elapsedMs <= 0 || frames <= 0) {
            return 0d;
        }
        double fps = frames * 1000d / elapsedMs;
        return Math.round(fps * 10d) / 10d;
    }

    /** 诊断条目（已打码）。 */
    public static JSONArray diagnostics(int limit) {
        int max = limit <= 0 ? DIAGNOSTIC_LIMIT : Math.min(limit, DIAGNOSTIC_LIMIT);
        JSONArray out = new JSONArray();
        synchronized (DIAGNOSTIC_LOCK) {
            int skip = Math.max(0, DIAGNOSTICS.size() - max);
            int index = 0;
            for (JSONObject item : DIAGNOSTICS) {
                if (index++ < skip) {
                    continue;
                }
                out.put(item);
            }
        }
        return out;
    }

    /** 记一条诊断（跨线程安全；凭证字段打码）。 */
    public static void record(String event, JSONObject details) {
        JSONObject entry = new JSONObject();
        put(entry, "at", System.currentTimeMillis());
        put(entry, "event", event == null ? "" : event);
        put(entry, "payload", CastProfile.redact(details == null ? new JSONObject() : details));
        synchronized (DIAGNOSTIC_LOCK) {
            DIAGNOSTICS.addLast(entry);
            while (DIAGNOSTICS.size() > DIAGNOSTIC_LIMIT) {
                DIAGNOSTICS.pollFirst();
            }
        }
    }

    /* ================= 事件 ================= */

    private void emitStatus() {
        JSONObject event = new JSONObject();
        put(event, "type", "status");
        put(event, "status", buildStatus());
        dispatch(event);
    }

    private void emitError(String code, String detail) {
        JSONObject event = new JSONObject();
        put(event, "type", "error");
        put(event, "error", code);
        put(event, "message", CastErrors.text(code));
        put(event, "detail", detail == null ? "" : detail);
        dispatch(event);
    }

    private void dispatch(final JSONObject event) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                EventListener listener = eventListener;
                if (listener == null) {
                    return;
                }
                try {
                    listener.onCastEvent(event);
                } catch (RuntimeException error) {
                    Log.w(TAG, "event listener threw: " + error);
                }
            }
        });
    }

    /* ================= 通知 ================= */

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("极核投屏（屏幕采集）");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("极核投屏")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setShowWhen(false);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                            : PendingIntent.FLAG_UPDATE_CURRENT);
            builder.setContentIntent(pending);
        }
        return builder.build();
    }

    private void startForegroundCompat(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, FGS_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException error) {
            // Android 14 缺 FOREGROUND_SERVICE_MEDIA_PROJECTION 权限/类型时会抛。
            // 这里不往上抛：抛出去服务直接崩，页面连错误码都拿不到。
            record("foreground-rejected", payload("message", String.valueOf(error)));
            emitError(CastErrors.SECURITY_ERROR, "startForeground: " + error);
        }
    }

    private void updateNotification() {
        if (!sessionActive) {
            return;
        }
        String text;
        if (PHASE_CASTING.equals(phase)) {
            text = "投屏中";
        } else if (PHASE_ADAPTING.equals(phase)) {
            text = "正在降档重试";
        } else if (PHASE_READY.equals(phase)) {
            text = "等待车机开始投屏";
        } else {
            text = "正在连接车机";
        }
        NotificationManager manager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            try {
                manager.notify(NOTIFICATION_ID, buildNotification(text));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (RuntimeException ignored) {
        }
    }

    /* ================= JSON 小工具 ================= */

    private static JSONObject payload(String key, Object value) {
        JSONObject json = new JSONObject();
        put(json, key, value);
        return json;
    }

    private static JSONObject payload(String key1, Object value1, String key2, Object value2) {
        JSONObject json = payload(key1, value1);
        put(json, key2, value2);
        return json;
    }

    private static JSONObject payload(String key1, Object value1, String key2, Object value2,
                                      String key3, Object value3) {
        JSONObject json = payload(key1, value1, key2, value2);
        put(json, key3, value3);
        return json;
    }

    private static JSONObject payload(String key1, Object value1, String key2, Object value2,
                                      String key3, Object value3, String key4, Object value4) {
        JSONObject json = payload(key1, value1, key2, value2, key3, value3);
        put(json, key4, value4);
        return json;
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (Exception ignored) {
            // 常量 key，不会抛
        }
    }
}
