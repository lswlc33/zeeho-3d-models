package io.github.lswlc33.zeehowebnext.cast;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 媒体控制通道（车机来连 10921）：采集配置、心跳、视图查询、开始投屏。
 *
 * <p>协议：{@code docs/CAST.md} §7，应答逐条对齐反编译 {@code r1.Q0}。
 *
 * <p>关键约束：
 * <ul>
 *   <li>应答的 {@code serial} 必须**原样回填**（车机拿它匹配请求，回 0 会被丢弃）；</li>
 *   <li>命令 17 的 9 字节首字段是**编码器号**（H.264 = 2），不是 level——
 *       它来自命令 16 偏移 8 的 encoder，缺省 2（w.b / p.b.f7894f）；</li>
 *   <li>extendedAck = “本次会话请求 extended” **且** “车机配置里支持 extended”，
 *       只有它为 1，10920 的数据帧才带 frameIndex（CAST.md §7.2）。</li>
 * </ul>
 */
public final class CastMediaChannel {

    private static final String TAG = "ZeehoCastMedia";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** 心跳空闲多久算车机没了。车机每 48/64 会来一次心跳，60 s 没有任何帧基本就是死了。
     *  〔未验证〕具体间隔未知，按 60 s 保守取值，宁可晚判断也不要误断。 */
    public static final long IDLE_TIMEOUT_MS = 60000L;

    /** 通道对服务端的依赖。 */
    public interface Host {
        /** 命令 16：登记车机下发的配置并返回档位规划（不能返回 null）。 */
        CastTiers.Plan onConfigure(CastWire.CaptureConfig config);

        /** 命令 112：起编码器；false 表示失败（通道会以 encoder_unavailable 收尾）。 */
        boolean onStartCasting();

        /** 当前档位规划；还没收到命令 16 时返回 null。 */
        CastTiers.Plan currentPlan();

        /** 本次会话是否请求 extended 数据帧（来自 start options）。 */
        boolean wantsExtendedFrames();

        /** 收发一帧，计数/诊断用。 */
        void onFrame(String direction, int command, int bytes);

        /** 通道结束（正常关闭也回调一次）。 */
        void onMediaClosed(String reason);
    }

    private final Host host;
    private final AtomicLong sessionSerial;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Socket socket;
    private volatile long lastFrameAt;

    public CastMediaChannel(Host host, AtomicLong sessionSerial) {
        this.host = host;
        this.sessionSerial = sessionSerial;
    }

    /** 阻塞服务一条 10921 连接。 */
    public void serve(Socket socket, long sessionId) {
        this.socket = socket;
        this.lastFrameAt = System.currentTimeMillis();
        String reason = "closed";
        try {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            while (isActive(sessionId)) {
                CastWire.MediaFrame frame;
                try {
                    frame = CastWire.MediaFrame.read(input);
                } catch (SocketTimeoutException timeout) {
                    // 10921 的 SO_TIMEOUT 只用来量“车机还在不在”，不是断链条件
                    if (System.currentTimeMillis() - lastFrameAt > IDLE_TIMEOUT_MS) {
                        reason = "vehicle_idle";
                        break;
                    }
                    continue;
                } catch (IOException error) {
                    reason = "media_frame_invalid";
                    break;
                }
                if (frame == null) {
                    reason = "peer_closed";
                    break;
                }
                lastFrameAt = System.currentTimeMillis();
                host.onFrame("rx", frame.command, frame.body.length);
                if (!dispatch(frame, output)) {
                    reason = "encoder_unavailable";
                    break;
                }
            }
            if (!isActive(sessionId)) {
                reason = "session_replaced";
            }
        } catch (IOException error) {
            reason = "media_io_error";
            Log.w(TAG, "media channel io: " + error.getClass().getSimpleName());
        } catch (RuntimeException error) {
            reason = "media_runtime_error";
            Log.w(TAG, "media channel runtime: " + error);
        } finally {
            closeQuietly(reason);
        }
    }

    private boolean isActive(long sessionId) {
        return !closed.get() && sessionSerial.get() == sessionId;
    }

    /**
     * 处理一条命令。
     *
     * @return false 表示需要断链（编码器起不来）
     */
    private boolean dispatch(CastWire.MediaFrame frame, OutputStream output) throws IOException {
        switch (frame.command) {
            case CastWire.CMD_CONFIGURE: {
                CastWire.CaptureConfig config;
                try {
                    config = CastWire.CaptureConfig.parse(frame.body);
                } catch (IOException error) {
                    // 配置非法：和 Motexa 一样让通道死掉，由上层决定降档还是失败
                    Log.w(TAG, "bad capture config: " + error.getMessage());
                    closeQuietly("media_config_invalid");
                    return false;
                }
                CastTiers.Plan plan = host.onConfigure(config);
                // extendedAck = 会话请求 extended && 车机配置支持 extended
                boolean extendedAck = host.wantsExtendedFrames() && config.extended;
                byte[] reply = CastWire.CaptureConfig.encoderReply(
                        plan.codec, plan.width, plan.height, extendedAck);
                reply(output, CastWire.CMD_CONFIGURE_ACK, frame.serial, reply);
                return true;
            }
            case CastWire.CMD_HEARTBEAT_A:
                // 固定 8 字节：int32 5002, int32 1（r1.v2）
                reply(output, CastWire.CMD_HEARTBEAT_A_REPLY, frame.serial,
                        CastWire.HEARTBEAT_REPLY);
                return true;
            case CastWire.CMD_HEARTBEAT_B:
                reply(output, CastWire.CMD_HEARTBEAT_B_REPLY, frame.serial, null);
                return true;
            case CastWire.CMD_VIEW_CONFIG:
                reply(output, CastWire.CMD_VIEW_CONFIG_REPLY, frame.serial,
                        CastWire.VIEW_CONFIG_REPLY_JSON.getBytes(UTF_8));
                return true;
            case CastWire.CMD_START: {
                // 车机点“开始投屏”：先起编码器，成功才回 113（顺序与 r1.Q0 一致）
                CastTiers.Plan plan = host.currentPlan();
                if (plan == null) {
                    Log.w(TAG, "start before configure");
                    closeQuietly("media_config_missing");
                    return false;
                }
                if (!host.onStartCasting()) {
                    closeQuietly("encoder_unavailable");
                    return false;
                }
                reply(output, CastWire.CMD_START_ACK, frame.serial, null);
                return true;
            }
            default:
                // 10921 上不该出现其它命令；忽略比回错包安全
                Log.i(TAG, "unexpected media command: " + frame.command);
                return true;
        }
    }

    private void reply(OutputStream output, int command, int serial, byte[] body)
            throws IOException {
        CastWire.MediaFrame frame = new CastWire.MediaFrame(command, serial, body);
        CastWire.writeFrame(output, frame.toBytes());
        host.onFrame("tx", command, frame.body.length);
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
            host.onMediaClosed(reason);
        } catch (RuntimeException error) {
            Log.w(TAG, "host threw: " + error);
        }
    }

    public void close() {
        closeQuietly("stopped");
    }
}
