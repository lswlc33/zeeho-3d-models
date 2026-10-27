package io.github.lswlc33.zeehowebnext.cast;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据通道（车机来连 10920）：车机“拉一帧”，手机回一包裸帧。
 *
 * <p>协议：{@code docs/CAST.md} §5.3 + §7 的 114，逻辑对齐反编译 {@code r1.R0}。
 *
 * <p>为什么是拉模型：车机按自己的解码节奏发 114，手机只负责“现在有没有新帧”。
 * 没帧时必须回**恰好 4 字节 0**（不是空响应、不是保持连接不动），否则车机会一直等。
 *
 * <p>帧序号：只有 extended 且真发了帧才自增；空包不消耗序号——否则车机侧会看到跳号。
 */
public final class CastFramePuller {

    private static final String TAG = "ZeehoCastFrames";

    /** 一次拉帧最多等这么久拿编码帧（r1.R0 的 {@code x(25L)}）。 */
    public static final long PULL_WAIT_MS = 25L;
    /** 连续空帧达到这个数就主动要一个关键帧：车机刚连上时缺 IDR 会一直黑屏。 */
    public static final int EMPTY_PULLS_BEFORE_KEYFRAME = 40;

    /** 对服务端的依赖。 */
    public interface Host {
        /** 当前是否已进入 casting（收到 112 并起好编码器）。 */
        boolean isCasting();

        /** 取一帧；没有就返回 null。允许阻塞至多 {@code timeoutMs}。 */
        CastEncoder.Frame pollFrame(long timeoutMs);

        /** 请求编码器出一个关键帧（内部有 300 ms 限流）。 */
        void requestKeyFrame();

        /** 成功发出一帧。 */
        void onFrameSent(int totalBytes, boolean keyframe);

        /** 回了一次空包。 */
        void onEmptyPull();

        /** 通道结束（正常关闭也回调一次）。 */
        void onDataClosed(String reason);
    }

    private final Host host;
    private final AtomicLong sessionSerial;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Socket socket;
    /** 由媒体通道在答复命令 17 后设置，决定本会话数据帧是否带 frameIndex。 */
    private volatile boolean extended;
    private int frameIndex;
    private int consecutiveEmptyPulls;

    private long framesSent;
    private long bytesSent;
    private long emptyPulls;
    private long keyFramesSent;
    private long lastSentAt;

    public CastFramePuller(Host host, AtomicLong sessionSerial) {
        this.host = host;
        this.sessionSerial = sessionSerial;
    }

    /**
     * 推流开始（回完 113）后把 10920 的读超时收紧到 2 s。
     *
     * <p>CAST.md §3.1：推流前给 15 s（车机还要走握手/等用户点开始），推流后必须 2 s——
     * 否则车机一卡顿，拉帧线程就挂在 read 上，会话结束都收不回来。
     */
    public void setCastingTimeout() {
        Socket current = socket;
        if (current == null) {
            return;
        }
        try {
            current.setSoTimeout(CastP2pLink.SO_TIMEOUT_DATA_CASTING);
        } catch (IOException error) {
            Log.w(TAG, "setSoTimeout failed: " + error);
        }
    }

    /** 媒体通道确定 extendedAck 后调用（必须在第一帧之前）。 */
    public void setExtended(boolean extended) {
        this.extended = extended;
        this.frameIndex = 0;
    }

    public boolean isExtended() {
        return extended;
    }

    /** 阻塞服务一条 10920 连接。 */
    public void serve(Socket socket, long sessionId) {
        this.socket = socket;
        String reason = "closed";
        try {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            while (isActive(sessionId)) {
                CastWire.MediaFrame request;
                try {
                    request = CastWire.MediaFrame.read(input);
                } catch (SocketTimeoutException timeout) {
                    // 10920 的 SO_TIMEOUT 只是“这一轮没拉到命令”，车机节奏由它自己定
                    continue;
                } catch (IOException error) {
                    reason = "data_frame_invalid";
                    break;
                }
                if (request == null) {
                    reason = "peer_closed";
                    break;
                }
                if (request.command != CastWire.CMD_PULL_FRAME) {
                    Log.i(TAG, "unexpected command on 10920: " + request.command);
                    continue;
                }
                if (!writeFrame(output, sessionId)) {
                    reason = "peer_gone";
                    break;
                }
            }
            if (!isActive(sessionId)) {
                reason = "session_replaced";
            }
        } catch (IOException error) {
            reason = "data_io_error";
            Log.w(TAG, "data channel io: " + error.getClass().getSimpleName());
        } catch (RuntimeException error) {
            reason = "data_runtime_error";
            Log.w(TAG, "data channel runtime: " + error);
        } finally {
            closeQuietly(reason);
        }
    }

    /**
     * 回一包：有帧就给帧，没帧给 4 字节 0。
     *
     * @return false 表示 socket 已不可写
     */
    private boolean writeFrame(OutputStream output, long sessionId) {
        CastEncoder.Frame frame = null;
        try {
            if (host.isCasting()) {
                frame = host.pollFrame(PULL_WAIT_MS);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "poll frame failed: " + error);
        }
        if (frame == null || frame.data == null || frame.data.length == 0) {
            emptyPulls++;
            consecutiveEmptyPulls++;
            try {
                CastWire.writeFrame(output, CastWire.encodeFramePacket(null, 0, false));
                host.onEmptyPull();
            } catch (IOException error) {
                return false;
            }
            if (consecutiveEmptyPulls >= EMPTY_PULLS_BEFORE_KEYFRAME) {
                consecutiveEmptyPulls = 0;
                host.requestKeyFrame();
            }
            return true;
        }
        consecutiveEmptyPulls = 0;
        byte[] packet;
        if (extended) {
            packet = CastWire.encodeFramePacket(frame.data, frameIndex, true);
            frameIndex++;
        } else {
            packet = CastWire.encodeFramePacket(frame.data, 0, false);
        }
        try {
            CastWire.writeFrame(output, packet);
        } catch (IOException error) {
            // 写给已断开的对端：车机已经走了，交给上层判定是否为降档
            return false;
        }
        framesSent++;
        bytesSent += packet.length;
        if (frame.keyframe) {
            keyFramesSent++;
        }
        lastSentAt = System.currentTimeMillis();
        host.onFrameSent(packet.length, frame.keyframe);
        return isActive(sessionId);
    }

    private boolean isActive(long sessionId) {
        return !closed.get() && sessionSerial.get() == sessionId;
    }

    /** 已发帧数（含空包之外的真实帧）。 */
    public long getFramesSent() {
        return framesSent;
    }

    public long getBytesSent() {
        return bytesSent;
    }

    public long getEmptyPulls() {
        return emptyPulls;
    }

    public long getKeyFramesSent() {
        return keyFramesSent;
    }

    /** 最近一次成功发帧的时间戳；0 表示还没发过。 */
    public long getLastSentAt() {
        return lastSentAt;
    }

    /** 幂等关闭 + 只回调一次。 */
    public void closeQuietly(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        try {
            host.onDataClosed(reason);
        } catch (RuntimeException error) {
            Log.w(TAG, "host threw: " + error);
        }
    }

    public void close() {
        closeQuietly("stopped");
    }
}
