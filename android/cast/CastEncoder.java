package io.github.lswlc33.zeehowebnext.cast;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕采集 + H.264 编码（MediaProjection → VirtualDisplay → MediaCodec Surface）。
 *
 * <p>参数来源：{@code docs/CAST.md} §8，逐项对齐反编译 {@code h2.o()}（MediaFormat）与
 * {@code h2.s()}（取帧、补 SPS/PPS），另外补了两处 CAST.md 没写、但代码里确实存在的细节：
 * <ul>
 *   <li><b>AUD 补齐</b>：编码器输出若没有 NAL type 9（access unit delimiter），
 *       要在帧前插 {@code 00 00 00 01 09 10}（h2.l）。部分车机解码器靠 AUD 切帧。</li>
 *   <li><b>关键帧补参数集</b>：关键帧里没有 SPS(7)/PPS(8) 时，把 csd-0 + csd-1 拼到帧前；
 *       csd 在 INFO_OUTPUT_FORMAT_CHANGED 时缓存。</li>
 * </ul>
 *
 * <p>为什么码率模式只在高优先级档位设 CBR：S905L3AB 一类盒子对 VBR 的瞬时码率峰很敏感，
 * CBR 能让它的解码缓冲可预测；而 compatibility 档为了兼容性优先，只改 profile/level，
 * 不再动码率模式（h2.o 的 z9 参数）。
 */
public final class CastEncoder {

    private static final String TAG = "ZeehoCastEncoder";

    /** H.264 MIME。 */
    public static final String MIME_AVC = "video/avc";
    /** COLOR_FormatSurface 的原始值（CAST.md §8；MediaFormat 用 int 传）。 */
    public static final int COLOR_FORMAT_SURFACE = 2130708361;
    /** KEY_BITRATE_MODE 的 CBR 常量值（MediaFormat 原生 2）。 */
    public static final int BITRATE_MODE_CBR_VALUE = 2;
    /** VirtualDisplay 的密度：Motexa 固定 160，不随屏幕 DPI 变（h2 里写死）。 */
    public static final int VIRTUAL_DISPLAY_DPI = 160;
    /** AUTO_MIRROR：把整屏镜像进这个 display。 */
    public static final int VIRTUAL_DISPLAY_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
    /** 编码器关键帧间隔：1 秒（CAST.md §8）。 */
    public static final int I_FRAME_INTERVAL_SECONDS = 1;
    /** 拉队列上限：拉模型下留太多帧只会让画面滞后，4 帧≈130 ms。 */
    public static final int FRAME_QUEUE_LIMIT = 4;
    /** request-sync 限流（h2.y 的 300 ms）。 */
    public static final long SYNC_REQUEST_INTERVAL_MS = 300L;
    /** 出队等待（us）。 */
    private static final long DEQUEUE_TIMEOUT_US = 10000L;
    /** 缺 AUD 时补的 6 字节：起始码 + NAL type 9 + primary_pic_type 0（h2.l）。 */
    private static final byte[] AUD_PREFIX = new byte[]{0, 0, 0, 1, 9, 0x10};

    /** 一帧编码结果（Annex-B）。 */
    public static final class Frame {
        public final byte[] data;
        public final boolean keyframe;
        public final long presentationTimeUs;

        Frame(byte[] data, boolean keyframe, long presentationTimeUs) {
            this.data = data;
            this.keyframe = keyframe;
            this.presentationTimeUs = presentationTimeUs;
        }
    }

    /** 编码器事件（都在编码器自己的线程上回调）。 */
    public interface Listener {
        /** 输出格式确定（编码器名/实际 bitrateMode 已可读）。 */
        void onFormatChanged(int width, int height, String codecName, String bitrateMode);

        /** 丢帧（拉模型下队列满会丢最老的帧）。 */
        void onFramesDropped(int dropped);

        /** 第一个关键帧就绪：此刻起车机才可能出画面。 */
        void onFirstKeyFrame();

        /** 致命错误（configure/start/编码异常），code 用 CastErrors。 */
        void onFatal(String code, String detail);

        /** MediaProjection 被系统/用户收回。 */
        void onProjectionStopped();
    }

    private final Listener listener;
    /** 发送端本机是否 S905L3A：只有它才加那组“高性能”编码键（v.d()，与档位/车机无关）。 */
    private final boolean highPerformanceKeys = CastTiers.isS905L3aSender();
    private final Object frameLock = new Object();
    private final ArrayDeque<Frame> frames = new ArrayDeque<Frame>();

    private MediaCodec codec;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private Thread drainThread;

    private volatile boolean running;
    private volatile String codecName = "";
    private volatile String bitrateMode = "default";
    private volatile int width;
    private volatile int height;
    private volatile int fps;
    private volatile int bitrate;

    private byte[] csd0 = new byte[0];
    private byte[] csd1 = new byte[0];
    private boolean csdWarningLogged;
    private long lastSyncRequestAt;
    private boolean firstKeyFrameNotified;

    private long framesProduced;
    private long framesDropped;
    private long bytesProduced;
    private long keyFramesProduced;

    public CastEncoder(Listener listener) {
        this.listener = listener;
    }

    /* ================= 生命周期 ================= */

    /**
     * 起编码器并开始镜像屏幕。
     *
     * @param mediaProjection 已授权的 MediaProjection（由前台服务拿到）
     * @param plan            档位规划后的实际参数
     * @return false 表示失败，原因已经通过 {@link Listener#onFatal} 报出
     */
    public boolean start(MediaProjection mediaProjection, final CastTiers.Plan plan) {
        if (running) {
            return true;
        }
        if (mediaProjection == null) {
            listener.onFatal(CastErrors.PROJECTION_PERMISSION_REQUIRED, "projection is null");
            return false;
        }
        this.projection = mediaProjection;
        this.width = plan.width;
        this.height = plan.height;
        this.fps = plan.fps;
        this.bitrate = plan.bitrate;
        this.bitrateMode = "default";
        this.firstKeyFrameNotified = false;
        this.csd0 = new byte[0];
        this.csd1 = new byte[0];
        this.csdWarningLogged = false;

        MediaFormat format = buildFormat(plan, true);
        try {
            String selected = selectEncoder(format);
            if (selected == null) {
                listener.onFatal(CastErrors.ENCODER_UNAVAILABLE, "no encoder for " + plan.width + "x" + plan.height);
                return false;
            }
            codec = MediaCodec.createByCodecName(selected);
            codecName = selected;
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (RuntimeException profileRejected) {
                // Motexa 那套 level id 不是标准 level_idc，个别 codec 会拒；按 codec-default 重配一次
                Log.w(TAG, "configure with profile/level rejected, retrying codec-default: "
                        + profileRejected);
                codec.reset();
                bitrateMode = "codec-default";
                codec.configure(buildFormat(plan, false), null, null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            inputSurface = codec.createInputSurface();
            codec.start();
        } catch (IOException error) {
            listener.onFatal(CastErrors.ENCODER_UNAVAILABLE, "configure: " + error);
            releaseCodecQuietly();
            return false;
        } catch (RuntimeException error) {
            listener.onFatal(CastErrors.ENCODER_UNAVAILABLE, "configure: " + error);
            releaseCodecQuietly();
            return false;
        }

        // API 34 起必须先注册 Callback 再 createVirtualDisplay，否则系统直接抛异常；
        // 低版本注册也无害，统一走同一条路径。
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "projection stopped by system");
                listener.onProjectionStopped();
            }
        };
        try {
            projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException error) {
            Log.w(TAG, "registerCallback failed: " + error);
        }

        try {
            virtualDisplay = projection.createVirtualDisplay("ZeehoCastCapture",
                    plan.width, plan.height, VIRTUAL_DISPLAY_DPI, VIRTUAL_DISPLAY_FLAGS,
                    inputSurface, null, new Handler(Looper.getMainLooper()));
            if (virtualDisplay == null) {
                listener.onFatal(CastErrors.PROJECTION_UNAVAILABLE, "virtual display is null");
                releaseCodecQuietly();
                return false;
            }
        } catch (RuntimeException error) {
            listener.onFatal(CastErrors.PROJECTION_UNAVAILABLE, "createVirtualDisplay: " + error);
            releaseCodecQuietly();
            return false;
        }

        running = true;
        drainThread = new Thread(new Runnable() {
            @Override
            public void run() {
                drainLoop();
            }
        }, "zeeho-cast-encoder");
        drainThread.setDaemon(true);
        drainThread.start();
        listener.onFormatChanged(plan.width, plan.height, codecName, bitrateMode);
        return true;
    }

    /**
     * 停编码器并释放全部资源。
     *
     * <p>顺序有讲究：先停 drain 线程，再停 codec（否则 dequeue 会一直抛 IllegalStateException），
     * 最后 release display/surface/projection。整段可以在任何线程调用，也可以重复调用。
     */
    public void stop() {
        running = false;
        synchronized (frameLock) {
            frames.clear();
            frameLock.notifyAll();
        }
        Thread thread = drainThread;
        drainThread = null;
        // 不要在 drain 线程里 join 自己（onFatal 可能就从那里回调过来），否则白等一个超时
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(800);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        releaseCodecQuietly();
        if (virtualDisplay != null) {
            try {
                virtualDisplay.release();
            } catch (RuntimeException ignored) {
            }
            virtualDisplay = null;
        }
        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (RuntimeException ignored) {
            }
            inputSurface = null;
        }
        if (projection != null) {
            MediaProjection.Callback callback = projectionCallback;
            projectionCallback = null;
            if (callback != null) {
                try {
                    projection.unregisterCallback(callback);
                } catch (RuntimeException ignored) {
                }
            }
            try {
                projection.stop();
            } catch (RuntimeException ignored) {
            }
            projection = null;
        }
        codecName = "";
    }

    private void releaseCodecQuietly() {
        MediaCodec current = codec;
        codec = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            current.release();
        } catch (RuntimeException ignored) {
        }
    }

    public boolean isRunning() {
        return running && codec != null;
    }

    /** 是否已经拿到 SPS/PPS（车机没有参数集就是黑屏）。 */
    public boolean hasParameterSets() {
        return csd0.length > 0 && csd1.length > 0;
    }

    public String getCodecName() {
        return codecName;
    }

    public String getBitrateMode() {
        return bitrateMode;
    }

    /* ================= MediaFormat（h2.o） ================= */

    /**
     * 组 MediaFormat。
     *
     * @param withProfileLevel 第一次 configure 一定带 Baseline + level（CAST.md §8）；
     *                         只有 configure 被 codec 拒绝后，才用 {@code false} 重配一次
     */
    private MediaFormat buildFormat(CastTiers.Plan plan, boolean withProfileLevel) {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_AVC, plan.width, plan.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FORMAT_SURFACE);
        format.setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);
        if (withProfileLevel) {
            format.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, plan.levelId);
        }
        // 这组键只在发送端本机命中 S905L3A 时加：桌面盒子的编码器认它们，手机 SoC 未必
        if (highPerformanceKeys) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, BITRATE_MODE_CBR_VALUE);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger("operating-rate", plan.fps);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                format.setInteger("latency", 1);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setFloat("max-fps-to-encoder", (float) plan.fps);
                format.setInteger("prepend-sps-pps-to-idr-frames", 1);
            }
        }
        return format;
    }

    /** 找编码器：先问系统“这个 format 谁最合适”，再退回第一个支持 avc 的编码器（h2.n）。 */
    private String selectEncoder(MediaFormat format) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            String name = list.findEncoderForFormat(format);
            if (name != null) {
                return name;
            }
            MediaCodecInfo[] infos = list.getCodecInfos();
            for (int i = 0; i < infos.length; i++) {
                MediaCodecInfo info = infos[i];
                if (!info.isEncoder()) {
                    continue;
                }
                String[] types = info.getSupportedTypes();
                for (int j = 0; j < types.length; j++) {
                    if (MIME_AVC.equalsIgnoreCase(types[j])) {
                        return info.getName();
                    }
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "selectEncoder failed: " + error);
        }
        return null;
    }

    /* ================= 取帧（h2.s） ================= */

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            MediaCodec current = codec;
            if (current == null) {
                return;
            }
            int index;
            try {
                index = current.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            } catch (IllegalStateException error) {
                if (running) {
                    Log.w(TAG, "dequeue failed: " + error);
                    listener.onFatal(CastErrors.ENCODER_UNAVAILABLE, "dequeue: " + error);
                }
                return;
            } catch (RuntimeException error) {
                if (running) {
                    Log.w(TAG, "dequeue runtime: " + error);
                }
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                readOutputFormat(current);
                continue;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            }
            if (index < 0) {
                continue;
            }
            handleOutputBuffer(current, index, info);
        }
    }

    private void handleOutputBuffer(MediaCodec source, int index, MediaCodec.BufferInfo info) {
        try {
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // 编码器把 csd 单独吐出来时也从这里取一次，作为 output-format 的补充
                ByteBuffer config = source.getOutputBuffer(index);
                if (config != null && info.size > 0) {
                    config.position(info.offset);
                    config.limit(info.offset + info.size);
                    byte[] bytes = new byte[info.size];
                    config.get(bytes);
                    cacheCsdFromCodecConfig(bytes);
                }
                return;
            }
            ByteBuffer buffer = source.getOutputBuffer(index);
            if (buffer == null || info.size <= 0) {
                return;
            }
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);
            byte[] data = new byte[info.size];
            buffer.get(data);

            boolean hasIdr = containsNalType(data, NAL_IDR);
            boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || hasIdr;
            data = ensureAccessUnitDelimiter(data);
            // 只有“确实带了 IDR 但缺 SPS/PPS”才补参数集；非 IDR 帧补了反而会被解码器当成参数集
            if (hasIdr && !hasSpsPps(data)) {
                data = prependParameterSets(data);
            }
            enqueue(new Frame(data, keyframe, info.presentationTimeUs));
        } catch (RuntimeException error) {
            Log.w(TAG, "handleOutputBuffer: " + error);
        } finally {
            try {
                source.releaseOutputBuffer(index, false);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void readOutputFormat(MediaCodec source) {
        try {
            MediaFormat format = source.getOutputFormat();
            byte[] csd = copyBuffer(format.getByteBuffer("csd-0"));
            byte[] csdPps = copyBuffer(format.getByteBuffer("csd-1"));
            if (csd.length > 0) {
                csd0 = csd;
            }
            if (csdPps.length > 0) {
                csd1 = csdPps;
            }
            int actualWidth = format.containsKey(MediaFormat.KEY_WIDTH)
                    ? format.getInteger(MediaFormat.KEY_WIDTH) : width;
            int actualHeight = format.containsKey(MediaFormat.KEY_HEIGHT)
                    ? format.getInteger(MediaFormat.KEY_HEIGHT) : height;
            if (format.containsKey(MediaFormat.KEY_BITRATE_MODE)) {
                int mode = format.getInteger(MediaFormat.KEY_BITRATE_MODE);
                bitrateMode = mode == BITRATE_MODE_CBR_VALUE ? "cbr" : ("mode-" + mode);
            }
            listener.onFormatChanged(actualWidth, actualHeight, codecName, bitrateMode);
        } catch (RuntimeException error) {
            Log.w(TAG, "readOutputFormat: " + error);
        }
    }

    /** 有些编码器只在 BUFFER_FLAG_CODEC_CONFIG 帧里给 csd；按 NAL 拆一次，分别塞进 csd0/csd1。 */
    private void cacheCsdFromCodecConfig(byte[] bytes) {
        List<int[]> starts = scanAnnexB(bytes);
        if (starts.isEmpty()) {
            if (csd0.length == 0) {
                csd0 = bytes;
            }
            return;
        }
        List<byte[]> sps = new ArrayList<byte[]>();
        List<byte[]> pps = new ArrayList<byte[]>();
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i)[0];
            int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : bytes.length;
            int header = starts.get(i)[1];
            int type = bytes[start + header] & 0x1F;
            byte[] nal = new byte[end - start];
            System.arraycopy(bytes, start, nal, 0, nal.length);
            if (type == NAL_SPS) {
                sps.add(nal);
            } else if (type == NAL_PPS) {
                pps.add(nal);
            }
        }
        if (!sps.isEmpty()) {
            csd0 = concat(sps);
        }
        if (!pps.isEmpty()) {
            csd1 = concat(pps);
        }
    }

    /**
     * 入队一帧；队列满时丢**最老的**帧（拉模型下旧帧只会增加画面延迟）。
     *
     * <p>为什么不做成无限队列：车机拉得慢时（比如 20 fps 档拉到 30 fps 的流），
     * 保旧帧等于让画面越拖越远，宁可丢帧保实时。
     */
    private void enqueue(Frame frame) {
        if (frame == null || frame.data.length == 0) {
            return;
        }
        try {
            int dropped = 0;
            synchronized (frameLock) {
                while (frames.size() >= FRAME_QUEUE_LIMIT) {
                    Frame evicted = frames.pollFirst();
                    if (evicted != null) {
                        dropped++;
                    } else {
                        break;
                    }
                }
                frames.addLast(frame);
                frameLock.notifyAll();
            }
            framesProduced++;
            bytesProduced += frame.data.length;
            if (dropped > 0) {
                framesDropped += dropped;
                listener.onFramesDropped(dropped);
                // 队列被清/丢帧后画面可能已经“跳”了，主动要一个 IDR 让车机尽快恢复
                requestKeyFrame();
            }
            if (frame.keyframe) {
                keyFramesProduced++;
                if (!firstKeyFrameNotified) {
                    firstKeyFrameNotified = true;
                    listener.onFirstKeyFrame();
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "enqueue: " + error);
        }
    }

    /**
     * 取一帧；没有就等最多 {@code timeoutMs}。
     *
     * <p>拉模型下“等不到”是常态（车机拉得比编码快），返回 null 即可，不要抛。
     */
    public Frame pollFrame(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        synchronized (frameLock) {
            while (frames.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0 || !running) {
                    return null;
                }
                try {
                    frameLock.wait(remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return frames.pollFirst();
        }
    }

    /** 请求关键帧（限流 300 ms，h2.y）。 */
    public void requestKeyFrame() {
        MediaCodec current = codec;
        if (current == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSyncRequestAt < SYNC_REQUEST_INTERVAL_MS) {
            return;
        }
        lastSyncRequestAt = now;
        try {
            Bundle params = new Bundle();
            params.putInt("request-sync", 0);
            current.setParameters(params);
        } catch (RuntimeException error) {
            Log.w(TAG, "request-sync failed: " + error);
        }
    }

    /* ================= H.264 工具 ================= */

    private static final int NAL_SPS = 7;
    private static final int NAL_PPS = 8;
    private static final int NAL_IDR = 5;
    private static final int NAL_AUD = 9;

    /** 扫描起始码，返回 {start, headerLen} 列表。 */
    static List<int[]> scanAnnexB(byte[] data) {
        List<int[]> starts = new ArrayList<int[]>();
        if (data == null) {
            return starts;
        }
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] != 0 || data[i + 1] != 0) {
                continue;
            }
            if (data[i + 2] == 1) {
                starts.add(new int[]{i, 3});
                i += 2;
            } else if (i + 4 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                starts.add(new int[]{i, 4});
                i += 3;
            }
        }
        return starts;
    }

    static boolean containsNalType(byte[] data, int nalType) {
        List<int[]> starts = scanAnnexB(data);
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i)[0];
            int header = starts.get(i)[1];
            if (start + header < data.length && (data[start + header] & 0x1F) == nalType) {
                return true;
            }
        }
        return false;
    }

    /** 帧里是否同时有 SPS(7) 和 PPS(8)。 */
    static boolean hasSpsPps(byte[] data) {
        return containsNalType(data, NAL_SPS) && containsNalType(data, NAL_PPS);
    }

    private static byte[] ensureAccessUnitDelimiter(byte[] data) {
        if (containsNalType(data, NAL_AUD)) {
            return data;
        }
        byte[] out = new byte[AUD_PREFIX.length + data.length];
        System.arraycopy(AUD_PREFIX, 0, out, 0, AUD_PREFIX.length);
        System.arraycopy(data, 0, out, AUD_PREFIX.length, data.length);
        return out;
    }

    private byte[] prependParameterSets(byte[] data) {
        if (csd0.length == 0 && csd1.length == 0) {
            if (!csdWarningLogged) {
                csdWarningLogged = true;
                Log.w(TAG, "keyframe without SPS/PPS and no csd cached; vehicle may not decode");
            }
            return data;
        }
        byte[] prefix = new byte[csd0.length + csd1.length];
        System.arraycopy(csd0, 0, prefix, 0, csd0.length);
        System.arraycopy(csd1, 0, prefix, csd0.length, csd1.length);
        byte[] out = new byte[prefix.length + data.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(data, 0, out, prefix.length, data.length);
        return out;
    }

    private static byte[] copyBuffer(ByteBuffer buffer) {
        if (buffer == null) {
            return new byte[0];
        }
        ByteBuffer duplicate = buffer.duplicate();
        byte[] out = new byte[duplicate.remaining()];
        duplicate.get(out);
        return out;
    }

    private static byte[] concat(List<byte[]> parts) {
        int total = 0;
        for (int i = 0; i < parts.size(); i++) {
            total += parts.get(i).length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (int i = 0; i < parts.size(); i++) {
            System.arraycopy(parts.get(i), 0, out, offset, parts.get(i).length);
            offset += parts.get(i).length;
        }
        return out;
    }

    /* ================= 统计 ================= */

    public long getFramesProduced() {
        return framesProduced;
    }

    public long getFramesDropped() {
        return framesDropped;
    }

    public long getBytesProduced() {
        return bytesProduced;
    }

    public long getKeyFramesProduced() {
        return keyFramesProduced;
    }

    /** 队列里待取的帧数（诊断用）。 */
    public int getQueuedFrames() {
        synchronized (frameLock) {
            return frames.size();
        }
    }
}
