package io.github.lswlc33.zeehowebnext.cast;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;

/**
 * 控制通道（车机来连 10922）：握手、客户端信息、SN 校验、start-signal。
 *
 * <p>协议：{@code docs/CAST.md} §6，应答逻辑逐条对齐反编译 {@code u.java}（{@code b()} 的
 * switch 就是下面这张表）。每条消息的 type 都是 32 位有符号数，因此
 * {@code 0x80000010} 在 switch 里是负数 {@code -2147483632}——这里用 int 常量直接比较，
 * 不需要区分符号。
 *
 * <p>为什么 RSA 只用 1024：车机固件只认这个长度（CAST.md §10）。不要把它当安全机制，
 * 它的唯一用途是“证明手机确实持有车机发来的 HUID”。
 */
public final class CastControlChannel {

    private static final String TAG = "ZeehoCastControl";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** 协议里写死的 pxcVersion（u.d）。 */
    public static final String PXC_VERSION = "1.0.2";
    /** 车机只用它做展示的蓝牙/设备名；这里用自己的品牌串。〔未验证〕车机是否校验。 */
    public static final String BLUETOOTH_NAME = "ZEEHO VehicleCast";
    /** RSA 位数（u.c）。 */
    public static final int RSA_KEY_BITS = 1024;

    /** 通道回调。所有方法都在通道自己的线程上调用，调用方负责切主线程。 */
    public interface Listener {
        /** 收到 start-signal（0x00030020），单向下发，不回包。 */
        void onStartSignal();

        /** 客户端信息应答是否成功发出（失败通常意味着 RSA/HUID 有问题）。 */
        void onClientInfoAnswered(boolean ok);

        /** SN 校验应答已发（两包）。 */
        void onSerialChecked(String sn, String clientSet);

        /** 收到/发出一帧，用于诊断计数。 */
        void onFrame(String direction, int type, int bytes);

        /** 通道结束（正常关闭也会回调一次）。 */
        void onControlClosed(String reason);
    }

    private final Context context;
    private final Listener listener;
    private final AtomicLong sessionSerial;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Socket socket;

    /**
     * 手机 UUID：**每个通道实例一个**。
     *
     * <p>为什么不是进程固定：Motexa 在 Q1（降档重试）里会 {@code new u(...)} 重建应答器，
     * UUID 与密钥对都跟着换新（r1.Q1 → new u）。车机只看这一次握手里的 pubkey 能不能验签，
     * 因此换新是安全的，照抄还能避免“同一 UUID/密钥被两条会话混用”的边界情况。
     */
    private final String phoneUuid = UUID.randomUUID().toString();
    /** RSA 密钥对：懒加载、实例级（同上）。 */
    private KeyPair keyPair;

    public CastControlChannel(Context context, Listener listener, AtomicLong sessionSerial) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.sessionSerial = sessionSerial;
    }

    /**
     * 阻塞地服务一条连接，直到对端关闭 / 校验失败 / 会话被替换。
     *
     * @param sessionId 当前会话号；{@code sessionSerial} 变了就主动收尾（幂等关 socket 的入口）
     */
    public void serve(Socket socket, long sessionId) {
        this.socket = socket;
        String reason = "closed";
        try {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            while (isActive(sessionId)) {
                CastWire.ControlFrame frame;
                try {
                    frame = CastWire.ControlFrame.read(input);
                } catch (IOException error) {
                    // 校验失败/半包：协议层面直接断链（CAST.md §5.1）
                    reason = "control_frame_invalid";
                    break;
                }
                if (frame == null) {
                    reason = "peer_closed";
                    break;
                }
                listener.onFrame("rx", frame.type, frame.body.length);
                if (!dispatch(frame, output)) {
                    reason = "control_protocol_error";
                    break;
                }
            }
            if (!isActive(sessionId)) {
                reason = "session_replaced";
            }
        } catch (IOException error) {
            reason = "control_io_error";
            Log.w(TAG, "control channel io: " + error.getClass().getSimpleName());
        } catch (RuntimeException error) {
            reason = "control_runtime_error";
            Log.w(TAG, "control channel runtime: " + error);
        } finally {
            closeQuietly(reason);
        }
    }

    /** 会话是否还在（服务端换 session 时会让旧通道自己退出）。 */
    private boolean isActive(long sessionId) {
        return !closed.get() && sessionSerial.get() == sessionId;
    }

    /**
     * 按消息表处理一帧。
     *
     * @return false 表示需要断链（客户端信息缺失/非法）
     */
    private boolean dispatch(CastWire.ControlFrame frame, OutputStream output) throws IOException {
        switch (frame.type) {
            case CastWire.MSG_PING:
            case CastWire.MSG_ACK:
            case CastWire.MSG_INFO_A:
            case CastWire.MSG_INFO_B:
                // 通用 ping/ack：type + 1，空 body（u.b 把 65536/67216/131072 合并成一条）
                send(output, frame.type + 1, null);
                return true;
            case CastWire.MSG_CLIENT_INFO_REQUEST:
                return answerClientInfo(frame, output);
            case CastWire.MSG_SERIAL_CHECK:
                answerSerialCheck(frame, output);
                return true;
            case CastWire.MSG_RUNNING_LOG:
                send(output, CastWire.MSG_RUNNING_LOG_ACK, null);
                return true;
            case CastWire.MSG_START_SIGNAL:
                // 单向下发，不回包；手机侧只需知道“车机要开始了”
                listener.onStartSignal();
                return true;
            case CastWire.MSG_UNSUPPORTED:
                // 车机告诉我们它不支持某命令：只记录，不回包（u.b 的 -2147483632 分支）
                Log.i(TAG, "vehicle reported unsupported command");
                return true;
            default:
                // 未知消息：静默忽略。回错包比不回更危险（车机会当成协议错）。
                Log.i(TAG, "unknown control message: 0x" + Integer.toHexString(frame.type));
                return true;
        }
    }

    /**
     * 0x00010010 → 0x00010011：把车机 HUID 用自己的私钥“加密”（等价签名）回传。
     *
     * <p>为什么用私钥做 ENCRYPT_MODE：车机拿到 pubkey 后能用它解出来，从而确认手机就是
     * 二维码这次配对的那一方（CAST.md §6.1）。HUID 为空视为非法，直接断链。
     */
    private boolean answerClientInfo(CastWire.ControlFrame frame, OutputStream output)
            throws IOException {
        try {
            JSONObject request = new JSONObject(frame.bodyText());
            String huid = request.optString("HUID", request.optString("huid", ""));
            if (huid.length() == 0) {
                Log.w(TAG, "client info without HUID");
                listener.onClientInfoAnswered(false);
                return false;
            }
            KeyPair pair = keyPair();
            JSONObject reply = new JSONObject();
            reply.put("pxcVersion", PXC_VERSION);
            reply.put("phoneUUID", phoneUuid);
            reply.put("phoneBrand", Build.BRAND == null ? "" : Build.BRAND);
            reply.put("phoneModel", Build.MODEL == null ? "" : Build.MODEL);
            reply.put("phoneOsVersion", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            reply.put("phoneOs", "Android");
            reply.put("package", context.getPackageName());
            reply.put("packageName", context.getPackageName());
            reply.put("versionCode", versionCode());
            reply.put("token", 0);
            reply.put("pubkey", Base64.encodeToString(pair.getPublic().getEncoded(), Base64.NO_WRAP));
            reply.put("encryptedHUID", signHuid(pair, huid));
            reply.put("bluetoothName", BLUETOOTH_NAME);
            // 能力声明：触摸/HID 一律 false——协议没有反向控制通道（CAST.md §10）
            reply.put("supportH264IFrame", true);
            reply.put("supportScreenTouch", false);
            reply.put("supportHID", false);
            reply.put("supportFunction", 0);
            reply.put("appVersionFingerPrint",
                    Build.FINGERPRINT == null ? "" : Build.FINGERPRINT);
            send(output, CastWire.MSG_CLIENT_INFO, reply.toString().getBytes(UTF_8));
            listener.onClientInfoAnswered(true);
            return true;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            Log.w(TAG, "client info failed: " + error);
            listener.onClientInfoAnswered(false);
            return false;
        }
    }

    /**
     * 0x000103E0 → 先 0x000103E1（空）再 0x000201C0（JSON）。
     *
     * <p>为什么分两包：车机固件先等一个“收到”的短 ack 再收结果体，合并成一包会让它
     * 一直等超时（CAST.md §6.2）。
     */
    private void answerSerialCheck(CastWire.ControlFrame frame, OutputStream output)
            throws IOException {
        String sn = "";
        String clientSet = "vehicle_cast";
        try {
            JSONObject request = new JSONObject(frame.bodyText());
            sn = request.optString("sn", "");
            clientSet = request.optString("client_set", "vehicle_cast");
        } catch (Exception error) {
            // body 不是 JSON：按空 sn 回，让车机自己判错（不在这里断链）
            Log.w(TAG, "serial check body is not json");
        }
        send(output, CastWire.MSG_SERIAL_ACK, null);
        JSONObject result = new JSONObject();
        try {
            result.put("isOk", true);
            result.put("errCode", 0);
            result.put("errMsg", "");
            result.put("id", sn);
            result.put("sn", sn);
            result.put("client_set", clientSet);
        } catch (Exception ignored) {
        }
        send(output, CastWire.MSG_SERIAL_RESULT, result.toString().getBytes(UTF_8));
        listener.onSerialChecked(sn, clientSet);
    }

    private void send(OutputStream output, int type, byte[] body) throws IOException {
        CastWire.ControlFrame frame = new CastWire.ControlFrame(type, body);
        CastWire.writeFrame(output, frame.toBytes());
        listener.onFrame("tx", type, frame.body.length);
    }

    /** 取本通道的密钥对（首次使用时生成）。 */
    private synchronized KeyPair keyPair() throws GeneralSecurityException {
        if (keyPair == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_BITS);
            keyPair = generator.generateKeyPair();
        }
        return keyPair;
    }

    /** 私钥加密 HUID（PKCS#1 v1.5），Base64 无换行（u.a）。 */
    private static String signHuid(KeyPair pair, String huid) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pair.getPrivate());
        return Base64.encodeToString(cipher.doFinal(huid.getBytes(UTF_8)), Base64.NO_WRAP);
    }

    /** 版本号：取不到就用 1（u.d 里 Motexa 也写死 1）。 */
    private int versionCode() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            return 1;
        }
    }

    /** 幂等关闭：socket 关一次，回调只发一次。 */
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
                // 已经断了，忽略
            }
        }
        try {
            listener.onControlClosed(reason);
        } catch (RuntimeException error) {
            Log.w(TAG, "listener threw: " + error);
        }
    }

    /** 外部主动关闭（服务停止/会话重试）。 */
    public void close() {
        closeQuietly("stopped");
    }
}
