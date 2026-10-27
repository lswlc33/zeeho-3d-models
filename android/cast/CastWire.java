package io.github.lswlc33.zeehowebnext.cast;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

/**
 * 线上格式（wire format）：常量 + 控制帧/媒体帧/数据帧编解码。
 *
 * <p>所有整数一律**小端**（CAST.md §5）。三个协议层的字节布局分别是：
 * <ul>
 *   <li>控制帧（10922 / 10930）：16 字节头 + body，校验 = {@code length ^ type}（§5.1，c0.a）；</li>
 *   <li>媒体帧（10921，10920 收命令）：8 字节头 + body（§5.2，c0.b）；</li>
 *   <li>数据帧（10920 回帧）：裸包 {@code [i32 len][可选 i32 index][Annex-B]}（§5.3）。</li>
 * </ul>
 *
 * <p>读方法都要区分“对端正常关闭”与“读到一半断了”：前者返回 {@code null}（循环自然退出），
 * 后者抛 {@link EOFException}（当作协议错误断链）。这是 c0.d 的原始语义，别改成
 * {@code readFully} 的返回 -1 风格，否则会把半包当空包回给车机。
 */
public final class CastWire {

    private CastWire() {
    }

    /* ================= 端口（CAST.md §3） ================= */

    /** 车机来连：裸数据帧（拉帧）。 */
    public static final int PORT_DATA = 10920;
    /** 车机来连：媒体控制帧。 */
    public static final int PORT_MEDIA = 10921;
    /** 车机来连：控制帧（JSON 握手）。 */
    public static final int PORT_CONTROL = 10922;
    /** 手机主动连：状态探测。 */
    public static final int PORT_STATUS = 10930;

    /** 车机是 GO，组网后地址通常是 192.168.0.1；探测时拿不到 groupOwnerAddress 的兜底（r1.n2）。 */
    public static final String FALLBACK_GO_IP = "192.168.0.1";

    /* ================= 控制帧（CAST.md §5.1 / §6） ================= */

    /** 控制帧头长度。 */
    public static final int CONTROL_HEADER = 16;
    /** 控制帧总长下限（= 头长度，body 可空）。 */
    public static final int CONTROL_MIN = 16;
    /** 控制帧总长上限（c0.a：16..262144）。 */
    public static final int CONTROL_MAX = 262144;

    /** 通用 ping。 */
    public static final int MSG_PING = 0x70000000;
    /** ping 应答 = 0x70000001。 */
    public static final int MSG_PONG = 0x70000001;
    /** 通用 ack。 */
    public static final int MSG_ACK = 0x00010000;
    public static final int MSG_ACK_REPLY = 0x00010001;
    /** 车机信息请求 A。 */
    public static final int MSG_INFO_A = 0x000106D0;
    public static final int MSG_INFO_A_REPLY = 0x000106D1;
    /** 车机信息请求 B。 */
    public static final int MSG_INFO_B = 0x00020000;
    public static final int MSG_INFO_B_REPLY = 0x00020001;
    /** 客户端信息（HUID 签名）。 */
    public static final int MSG_CLIENT_INFO_REQUEST = 0x00010010;
    public static final int MSG_CLIENT_INFO = 0x00010011;
    /** SN 校验：请求 → 先空 ack，再 JSON 结果。 */
    public static final int MSG_SERIAL_CHECK = 0x000103E0;
    public static final int MSG_SERIAL_ACK = 0x000103E1;
    public static final int MSG_SERIAL_RESULT = 0x000201C0;
    /** running-log。 */
    public static final int MSG_RUNNING_LOG = 0x00010780;
    public static final int MSG_RUNNING_LOG_ACK = 0x00010781;
    /** start-signal：单向下发，不回包。 */
    public static final int MSG_START_SIGNAL = 0x00030020;
    /** 不支持的命令（0x80000010）：只记日志，不回包（u.b 的 -2147483632 分支）。 */
    public static final int MSG_UNSUPPORTED = 0x80000010;

    /** 状态探测请求/应答（CAST.md §4，r1.n2）：0x70000010 → 0x70000011 + {"status":true}。 */
    public static final int STATUS_PROBE_REQUEST = 0x70000010;
    public static final int STATUS_PROBE_RESPONSE = 0x70000011;

    /** 状态探测的超时（r1.n2 写死 4000/4000）。 */
    public static final int STATUS_PROBE_CONNECT_TIMEOUT_MS = 4000;
    public static final int STATUS_PROBE_READ_TIMEOUT_MS = 4000;

    /* ================= 媒体帧（CAST.md §5.2 / §7） ================= */

    /** 媒体帧头长度。 */
    public static final int MEDIA_HEADER = 8;
    /** 媒体帧 body 上限（uint16）。 */
    public static final int MEDIA_BODY_MAX = 65535;

    /** 采集配置（车机 → 手机）。 */
    public static final int CMD_CONFIGURE = 16;
    /** 编码器确认（手机 → 车机，9 字节）。 */
    public static final int CMD_CONFIGURE_ACK = 17;
    public static final int CMD_HEARTBEAT_A = 48;
    /** 心跳应答，固定 8 字节：int32 5002, int32 1（r1.v2）。 */
    public static final int CMD_HEARTBEAT_A_REPLY = 49;
    public static final int CMD_HEARTBEAT_B = 64;
    public static final int CMD_HEARTBEAT_B_REPLY = 65;
    /** 查询视图配置。 */
    public static final int CMD_VIEW_CONFIG = 96;
    public static final int CMD_VIEW_CONFIG_REPLY = 97;
    /** 开始投屏（手机起编码器后回 113）。 */
    public static final int CMD_START = 112;
    public static final int CMD_START_ACK = 113;
    /** 拉一帧（只在 10920 上收）。 */
    public static final int CMD_PULL_FRAME = 114;

    /** 采集配置最小字节数（w.a）。 */
    public static final int CAPTURE_CONFIG_MIN_BYTES = 30;
    /** 采集配置缺省码率：2 MiB（w.a / CAST.md §7.1）。 */
    public static final int DEFAULT_BITRATE = 2097152;
    /** encoder 字段的 H.264 取值（w.a：0 也当 2）。 */
    public static final int CODEC_AVC = 2;

    /** 心跳应答常量：{@code 5002, 1}。 */
    public static final byte[] HEARTBEAT_REPLY = new byte[]{(byte) 0x8a, 0x13, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00};

    /** 视图配置应答 JSON（r1.w2，字段顺序无关，车机只读 key）。 */
    public static final String VIEW_CONFIG_REPLY_JSON =
            "{\"viewAreaConfig\":{\"state\":0},\"supportFunction\":0}";

    /** 单帧上限：仅用于防御异常长度，协议本身 i32 无上限（CAST.md §5.3）。 */
    public static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /* ================= 控制帧 ================= */

    /** 控制帧（type/code/body）。 */
    public static final class ControlFrame {
        public final int type;
        public final int code;
        public final byte[] body;

        public ControlFrame(int type, byte[] body) {
            this(type, 0, body);
        }

        public ControlFrame(int type, int code, byte[] body) {
            this.type = type;
            this.code = code;
            this.body = body == null ? new byte[0] : body;
        }

        /** 带 JSON body 的便捷构造（body 为 null/空时就是空 body）。 */
        public static ControlFrame json(int type, String json) {
            return new ControlFrame(type, json == null ? new byte[0] : json.getBytes(UTF_8));
        }

        public String bodyText() {
            return body.length == 0 ? "" : new String(body, UTF_8);
        }

        public byte[] toBytes() throws IOException {
            int length = body.length + CONTROL_HEADER;
            if (length > CONTROL_MAX) {
                throw new IOException("Control frame is too large.");
            }
            ByteBuffer buffer = ByteBuffer.allocate(CONTROL_HEADER).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(type).putInt(length).putInt(length ^ type).putInt(code);
            byte[] header = buffer.array();
            byte[] out = new byte[length];
            System.arraycopy(header, 0, out, 0, CONTROL_HEADER);
            System.arraycopy(body, 0, out, CONTROL_HEADER, body.length);
            return out;
        }

        /**
         * 读一整帧；对端正常关闭返回 {@code null}。
         *
         * @throws IOException 长度越界、校验不符、读到一半断开
         */
        public static ControlFrame read(InputStream input) throws IOException {
            byte[] header = readFullyOrEof(input, CONTROL_HEADER);
            if (header == null) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int type = buffer.getInt();
            int length = buffer.getInt();
            int checksum = buffer.getInt();
            int code = buffer.getInt();
            if (length < CONTROL_MIN || length > CONTROL_MAX) {
                throw new IOException("Invalid control frame length: " + length);
            }
            if ((length ^ type) != checksum) {
                throw new IOException("Invalid control frame checksum.");
            }
            byte[] body = readFully(input, length - CONTROL_HEADER);
            return new ControlFrame(type, code, body);
        }
    }

    /* ================= 媒体帧 ================= */

    /** 媒体帧（command/bodyLength/serial/body）。 */
    public static final class MediaFrame {
        public final int command;
        public final int serial;
        public final byte[] body;

        public MediaFrame(int command, int serial, byte[] body) {
            if (command < 0 || command > 0xFFFF) {
                throw new IllegalArgumentException("Media command out of range: " + command);
            }
            byte[] payload = body == null ? new byte[0] : body;
            if (payload.length > MEDIA_BODY_MAX) {
                throw new IllegalArgumentException("Media body is too large: " + payload.length);
            }
            this.command = command;
            this.serial = serial;
            this.body = payload;
        }

        /** 构造一个 body 为空的应答，serial 原样回填。 */
        public static MediaFrame emptyReply(int command, int serial) {
            return new MediaFrame(command, serial, new byte[0]);
        }

        public String bodyText() {
            return body.length == 0 ? "" : new String(body, UTF_8);
        }

        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(MEDIA_HEADER + body.length)
                    .order(ByteOrder.LITTLE_ENDIAN);
            buffer.putShort((short) command).putShort((short) body.length).putInt(serial);
            byte[] out = buffer.array();
            System.arraycopy(body, 0, out, MEDIA_HEADER, body.length);
            return out;
        }

        /** 同 {@link ControlFrame#read}：正常关闭返回 null。 */
        public static MediaFrame read(InputStream input) throws IOException {
            byte[] header = readFullyOrEof(input, MEDIA_HEADER);
            if (header == null) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int command = buffer.getShort() & 0xFFFF;
            int bodyLength = buffer.getShort() & 0xFFFF;
            int serial = buffer.getInt();
            if (bodyLength > MEDIA_BODY_MAX) {
                throw new IOException("Invalid media frame length: " + bodyLength);
            }
            byte[] body = readFully(input, bodyLength);
            return new MediaFrame(command, serial, body);
        }
    }

    /* ================= 数据帧（裸包） ================= */

    /** 解出来的数据帧。 */
    public static final class FramePacket {
        public final int length;
        /** extended 时才有值，否则 -1。 */
        public final int index;
        public final byte[] frame;

        FramePacket(int length, int index, byte[] frame) {
            this.length = length;
            this.index = index;
            this.frame = frame;
        }
    }

    /**
     * 组一个数据帧裸包。{@code frame} 为空时就是 4 字节 0（“暂时没有新帧”，车机会重试）。
     *
     * <p>**length 的语义（容易写错的地方）**：它是 {@code length} 字段之后的全部字节数，
     * 所以 extended 带帧时是「帧长 + 4（frameIndex 那 4 字节）」，传统模式等于帧长。
     * 空包固定只有 4 字节 0，extended 也不带 index（CAST.md §5.3 / r1.R0）。
     */
    public static byte[] encodeFramePacket(byte[] frame, int index, boolean extended) {
        byte[] payload = frame == null ? new byte[0] : frame;
        boolean withIndex = extended && payload.length > 0;
        int header = withIndex ? 8 : 4;
        int length = payload.length + (withIndex ? 4 : 0);
        ByteBuffer buffer = ByteBuffer.allocate(header + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(length);
        if (withIndex) {
            buffer.putInt(index);
        }
        byte[] out = buffer.array();
        if (payload.length > 0) {
            System.arraycopy(payload, 0, out, header, payload.length);
        }
        return out;
    }

    /** 解析裸包（给自检/诊断用；真实链路只写不读）。 */
    public static FramePacket decodeFramePacket(byte[] bytes, boolean extended) throws IOException {
        if (bytes == null || bytes.length < 4) {
            throw new IOException("Truncated frame packet.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int length = buffer.getInt(0);
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Invalid frame length: " + length);
        }
        if (length == 0) {
            return new FramePacket(0, -1, new byte[0]);
        }
        if (!extended) {
            if (bytes.length < 4 + length) {
                throw new IOException("Truncated frame body.");
            }
            byte[] plain = new byte[length];
            System.arraycopy(bytes, 4, plain, 0, length);
            return new FramePacket(length, -1, plain);
        }
        // extended：length 里含 4 字节 frameIndex
        int frameLength = length - 4;
        if (frameLength < 0 || bytes.length < 4 + length) {
            throw new IOException("Truncated frame body.");
        }
        int index = buffer.getInt(4);
        byte[] frame = new byte[frameLength];
        System.arraycopy(bytes, 8, frame, 0, frameLength);
        return new FramePacket(length, index, frame);
    }

    /* ================= 采集配置（CAST.md §7.1，w.a） ================= */

    /** 车机下发的采集配置。 */
    public static final class CaptureConfig {
        public final int width;
        public final int height;
        public final int fps;
        /** 编码器号，0 已归一成 2（H.264）。 */
        public final int encoder;
        public final int bitrate;
        /** 车机是否支持 extended 数据帧（第 29 字节）。 */
        public final boolean extended;

        CaptureConfig(int width, int height, int fps, int encoder, int bitrate, boolean extended) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.encoder = encoder;
            this.bitrate = bitrate;
            this.extended = extended;
        }

        /**
         * 解析 30 字节配置。
         *
         * @throws IOException 长度不足或宽高 < 16（w.a 的两条 IOException）
         */
        public static CaptureConfig parse(byte[] body) throws IOException {
            if (body == null || body.length < CAPTURE_CONFIG_MIN_BYTES) {
                throw new IOException("Capture configuration is too short.");
            }
            ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
            int width = buffer.getShort(0) & 0xFFFF;
            int height = buffer.getShort(2) & 0xFFFF;
            if (width < 16 || height < 16) {
                throw new IOException("Invalid capture dimensions: " + width + "x" + height);
            }
            int fps = buffer.getInt(4);
            int encoder = buffer.getInt(8);
            if (encoder == 0) {
                encoder = CODEC_AVC;
            }
            int bitrate = buffer.getInt(20);
            boolean extended = body[29] != 0;
            return new CaptureConfig(width, height, fps, encoder, bitrate, extended);
        }

        /** 自检用：组一个 30 字节配置（与 buildCaptureConfig 同布局）。 */
        public static byte[] build(int width, int height, int fps, int encoder, int bitrate,
                                   boolean extended) {
            ByteBuffer buffer = ByteBuffer.allocate(CAPTURE_CONFIG_MIN_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            buffer.putShort(0, (short) width).putShort(2, (short) height);
            buffer.putInt(4, fps).putInt(8, encoder).putInt(20, bitrate);
            byte[] out = buffer.array();
            out[29] = (byte) (extended ? 1 : 0);
            return out;
        }

        /** 命令 17 的 9 字节应答：int32 codec, uint16 width, uint16 height, uint8 extendedAck。 */
        public static byte[] encoderReply(int codec, int width, int height, boolean extendedAck) {
            ByteBuffer buffer = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(codec).putShort((short) width).putShort((short) height)
                    .put((byte) (extendedAck ? 1 : 0));
            return buffer.array();
        }
    }

    /* ================= 流式读 ================= */

    /**
     * 读满 {@code length} 字节；**一个字节都没读到时返回 null**（对端正常关闭）。
     * 读到一半断开抛 {@link EOFException}。
     */
    static byte[] readFullyOrEof(InputStream input, int length) throws IOException {
        if (length == 0) {
            return new byte[0];
        }
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read;
            try {
                read = input.read(buffer, offset, length - offset);
            } catch (IOException error) {
                if (offset == 0) {
                    // 尚未读到任何字节：可能是对端关闭，也可能是 socket 超时。交给调用方按
                    // 场景判断（10920 的 SO_TIMEOUT 是正常现象，控制通道则算异常）。
                    throw error;
                }
                throw new EOFException("Truncated frame header.");
            }
            if (read < 0) {
                if (offset == 0) {
                    return null;
                }
                throw new EOFException("Truncated frame header.");
            }
            offset += read;
        }
        return buffer;
    }

    /** 读满 {@code length} 字节，中途 EOF 抛 {@link EOFException}。 */
    static byte[] readFully(InputStream input, int length) throws IOException {
        if (length == 0) {
            return new byte[0];
        }
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new EOFException("Truncated frame body.");
            }
            offset += read;
        }
        return buffer;
    }

    /** 一帧写下去并 flush（车机侧是按帧解析的，flush 不能省）。 */
    static void writeFrame(OutputStream output, byte[] bytes) throws IOException {
        output.write(bytes);
        output.flush();
    }
}
