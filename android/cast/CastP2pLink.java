package io.github.lswlc33.zeehowebnext.cast;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wi-Fi Direct 组网 + 三个监听端口 + 10930 状态探测。
 *
 * <p>协议：{@code docs/CAST.md} §3 / §4，流程对齐反编译 {@code r1}（{@code l0/r1/d1} 组网、
 * {@code k1} 监听、{@code n2} 状态探测、{@code G0} 来源校验）。
 *
 * <p>角色是**固定的**：车机是 GO，手机是 client。因此手机必须
 * {@code groupOwnerIntent = 0} 让车机当组主，并且监听在手机侧等车机连回来。
 *
 * <p>三个坑（真机上一定会踩）：
 * <ol>
 *   <li>探测 10930 时必须**把 socket 绑到 P2P 网络**，否则 4G/家用 Wi-Fi 的路由会让
 *       192.168.0.1 走错出口（r1.u2 的做法：找与目标同 /24 的 Network，用它的 SocketFactory）；</li>
 *   <li>三个 ServerSocket 只接受来源 IP == GO 地址的连接，其它一律关掉（r1.G0）；</li>
 *   <li>connect 成功后 <b>不能</b>立刻认为组已形成，要等
 *       {@code WIFI_P2P_CONNECTION_CHANGED} + {@code requestConnectionInfo} 拿到 groupFormed。</li>
 * </ol>
 */
public final class CastP2pLink {

    private static final String TAG = "ZeehoCastP2p";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** 组网总超时。〔未验证〕Motexa 内部用 20 s 的重试定时器；这里给 30 s，避免弱机型误判。 */
    public static final long P2P_TIMEOUT_MS = 30000L;
    /** accept 超时：只是为了让线程能定期检查会话是否还活着。 */
    public static final int ACCEPT_TIMEOUT_MS = 1000;
    /** 10922 控制通道：不设读超时，靠对端关闭/会话结束收尾。 */
    public static final int SO_TIMEOUT_CONTROL = 0;
    /** 10921 媒体通道：15 s 没任何帧就认为车机静默（r1.k1 对非 10920 端口的选择）。 */
    public static final int SO_TIMEOUT_MEDIA = 15000;
    /** 10920 推流**前**：15 s（车机可能还在等 112）。 */
    public static final int SO_TIMEOUT_DATA_BEFORE_CAST = 15000;
    /** 10920 推流开始**后**：2 s，否则车机卡顿时会把拉帧线程挂死（CAST.md §3.1）。 */
    public static final int SO_TIMEOUT_DATA_CASTING = 2000;

    /** Android 13 起读取 P2P 扫描结果需要它（字符串字面量，避免 compileSdk < 33 编不过）。 */
    public static final String PERMISSION_NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES";
    /** Context.RECEIVER_NOT_EXPORTED 的数值（API 33 才有该常量，故用字面量保证低 compileSdk 可读）。 */
    private static final int FLAG_RECEIVER_NOT_EXPORTED = 0x0004;

    /** 状态探测请求体（CAST.md §4）。 */
    public static final String PROBE_BODY_TEMPLATE = "{\"phoneType\":\"Android\",\"packageName\":\"%s\"}";

    /** 组网/监听事件回调。全部在自己的线程上调用，调用方负责切主线程。 */
    public interface Callback {
        /** 阶段变化（provisioning / handshaking / ready）。 */
        void onPhase(String phase, String reason);

        /** 失败（错误码见 CastErrors）。 */
        void onError(String code, String detail);

        /** 状态探测通过（车机在线）。 */
        void onProbed(String goAddress, long elapsedMs);

        /** 三个端口已就绪，可以等车机连进来了。 */
        void onListenersReady();

        /** 一个通过 GO 来源校验、已设好 TCP_NODELAY/SO_TIMEOUT 的连接。 */
        void onSocket(int port, Socket socket);

        /** 诊断日志。 */
        void onLog(String event, JSONObject payload);
    }

    private final Context context;
    private final Callback callback;
    private final AtomicLong sessionSerial;
    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private CastProfile profile;
    private volatile String goAddress;

    private final List<ServerSocket> serverSockets = new ArrayList<ServerSocket>();
    private final List<Thread> threads = new ArrayList<Thread>();
    private final List<Socket> acceptedSockets = new ArrayList<Socket>();

    private long sessionId;
    private Runnable timeoutTask;

    public CastP2pLink(Context context, Callback callback, AtomicLong sessionSerial) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.sessionSerial = sessionSerial;
    }

    /* ================= 环境检查（CAST.md §3 步骤 1） ================= */

    /** 机型是否支持 Wi-Fi Direct。 */
    public static boolean hasWifiDirect(Context context) {
        PackageManager pm = context.getPackageManager();
        return pm != null && pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    }

    /**
     * 检查前置条件。
     *
     * @return null 表示可以开始；否则是 CastErrors 里的错误码
     */
    public String checkEnvironment() {
        if (!hasWifiDirect(context)) {
            return CastErrors.WIFI_DIRECT_UNSUPPORTED;
        }
        if (!hasPermission()) {
            return CastErrors.WIFI_PERMISSION_DENIED;
        }
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            return CastErrors.WIFI_DISABLED;
        }
        if (isHotspotEnabled(wifiManager)) {
            // 热点开着时 P2P 组网会抢同一个射频，必须先关（CAST.md §3）
            return CastErrors.HOTSPOT_ACTIVE;
        }
        return null;
    }

    /** 权限：Android 13+ 用 NEARBY_WIFI_DEVICES，13 以下用定位权限。 */
    public boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 33 /* TIRAMISU */) {
            return context.checkSelfPermission(PERMISSION_NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 热点检测：公开 API 至今没有查询热点的接口，只能反射 {@code getWifiApState}。
     *
     * <p>〔未验证〕不同 ROM 上反射可能失败/返回不同值；失败时按“没开热点”处理，
     * 宁可让组网自己去撞错，也不要在某些机型上永远起不来。
     */
    private boolean isHotspotEnabled(WifiManager wifiManager) {
        if (wifiManager == null) {
            return false;
        }
        try {
            Object state = wifiManager.getClass().getMethod("getWifiApState").invoke(wifiManager);
            if (state instanceof Integer) {
                int value = (Integer) state;
                // 13 = WIFI_AP_STATE_ENABLED（部分 ROM 用 12 表示 enabling）
                return value == 13;
            }
        } catch (Exception ignored) {
            // 反射失败：当作没开
        }
        return false;
    }

    /* ================= 组网主流程 ================= */

    /** 开始组网（幂等：重复调用直接返回）。 */
    public void start(CastProfile target, long sessionId) {
        if (!stopped.compareAndSet(true, false)) {
            return;
        }
        this.profile = target;
        this.sessionId = sessionId;
        this.goAddress = null;
        String envError = checkEnvironment();
        if (envError != null) {
            callback.onError(envError, "precondition failed");
            stop();
            return;
        }
        callback.onPhase("provisioning", "");
        try {
            manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
            if (manager == null) {
                callback.onError(CastErrors.WIFI_DIRECT_UNSUPPORTED, "no WifiP2pManager");
                stop();
                return;
            }
            channel = manager.initialize(context, Looper.getMainLooper(),
                    new WifiP2pManager.ChannelListener() {
                        @Override
                        public void onChannelDisconnected() {
                            if (!stopped.get()) {
                                callback.onError(CastErrors.WIFI_DIRECT_DISABLED, "channel disconnected");
                                stop();
                            }
                        }
                    });
            registerReceiver();
            scheduleTimeout();
            // 上一轮可能残留组：清掉再扫，否则 discoverPeers 会一直 BUSY
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    discover();
                }

                @Override
                public void onFailure(int reason) {
                    // BUSY/ERROR 都继续扫；真起不来会走到超时
                    discover();
                }
            });
        } catch (SecurityException error) {
            callback.onError(CastErrors.SECURITY_ERROR, "p2p init: " + error);
            stop();
        } catch (RuntimeException error) {
            callback.onError(CastErrors.P2P_CONNECT_FAILED, "p2p init: " + error);
            stop();
        }
    }

    private void discover() {
        if (!isActive()) {
            return;
        }
        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    log("p2p-discovery-started", null);
                }

                @Override
                public void onFailure(int reason) {
                    // 正在扫描时也会失败：不当致命错误，等 PEERS_CHANGED
                    log("p2p-discovery-failed", payload("reason", reason));
                }
            });
        } catch (SecurityException error) {
            callback.onError(CastErrors.WIFI_PERMISSION_DENIED, "discoverPeers: " + error);
        } catch (RuntimeException error) {
            callback.onError(CastErrors.P2P_CONNECT_FAILED, "discoverPeers: " + error);
        }
    }

    /** 在扫描结果里找二维码里的 MAC（比 deviceName 可靠，车机名字会变）。 */
    private void onPeersAvailable(WifiP2pDeviceList peers) {
        if (!isActive() || peers == null || profile == null) {
            return;
        }
        WifiP2pDevice target = null;
        for (WifiP2pDevice device : peers.getDeviceList()) {
            if (device == null || device.deviceAddress == null) {
                continue;
            }
            if (profile.p2pMac.equalsIgnoreCase(device.deviceAddress)) {
                target = device;
                break;
            }
        }
        JSONObject payload = payload("peerCount", peers.getDeviceList().size());
        put(payload, "targetFound", target != null);
        log("p2p-peer-list", payload);
        if (target == null) {
            // 继续等下一轮 PEERS_CHANGED；超时会报 p2p_timeout
            return;
        }
        connect(target.deviceAddress);
    }

    private void connect(String deviceAddress) {
        try {
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = deviceAddress;
            // PBC：二维码里的口令是组建立之后 Wi-Fi 层用的，这里不需要 PIN
            config.wps.setup = WpsInfo.PBC;
            // 0 = 尽量不当 GO：车机必须是组主，否则 192.168.0.1 这套约定就不成立
            config.groupOwnerIntent = 0;
            log("p2p-connect-requested", payload("deviceAddress",
                    CastProfile.maskP2pMac(deviceAddress)));
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    log("p2p-connect-accepted", null);
                }

                @Override
                public void onFailure(int reason) {
                    callback.onError(CastErrors.P2P_CONNECT_FAILED, "connect reason=" + reason);
                    stop();
                }
            });
        } catch (SecurityException error) {
            callback.onError(CastErrors.WIFI_PERMISSION_DENIED, "connect: " + error);
            stop();
        } catch (RuntimeException error) {
            callback.onError(CastErrors.P2P_CONNECT_FAILED, "connect: " + error);
            stop();
        }
    }

    /** 组形成后拿 GO 地址，然后做 10930 探测。 */
    private void requestConnectionInfo() {
        if (!isActive()) {
            return;
        }
        try {
            manager.requestConnectionInfo(channel, new WifiP2pManager.ConnectionInfoListener() {
                @Override
                public void onConnectionInfoAvailable(WifiP2pInfo info) {
                    if (!isActive()) {
                        return;
                    }
                    if (info == null || !info.groupFormed) {
                        log("p2p-group-not-formed", null);
                        return;
                    }
                    String host = info.groupOwnerAddress == null
                            ? CastWire.FALLBACK_GO_IP
                            : info.groupOwnerAddress.getHostAddress();
                    log("p2p-group-formed", payload("groupOwnerAddress", host));
                    if (!info.isGroupOwner) {
                        goAddress = host;
                        probe(host);
                    } else {
                        // 车机没抢到 GO：这套协议不支持手机当组主（车机只连 192.168.0.1 自己的网段）
                        callback.onError(CastErrors.P2P_CONNECT_FAILED, "phone became group owner");
                        stop();
                    }
                }
            });
        } catch (SecurityException error) {
            callback.onError(CastErrors.WIFI_PERMISSION_DENIED, "requestConnectionInfo: " + error);
        } catch (RuntimeException error) {
            callback.onError(CastErrors.P2P_CONNECT_FAILED, "requestConnectionInfo: " + error);
        }
    }

    /* ================= 10930 状态探测（CAST.md §4） ================= */

    private void probe(final String host) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                long startedAt = System.currentTimeMillis();
                Socket socket = null;
                try {
                    socket = newSocketBoundTo(host);
                    socket.connect(new InetSocketAddress(host, CastWire.PORT_STATUS),
                            CastWire.STATUS_PROBE_CONNECT_TIMEOUT_MS);
                    socket.setSoTimeout(CastWire.STATUS_PROBE_READ_TIMEOUT_MS);
                    String body = String.format(PROBE_BODY_TEMPLATE, context.getPackageName());
                    OutputStream output = socket.getOutputStream();
                    CastWire.writeFrame(output, new CastWire.ControlFrame(
                            CastWire.STATUS_PROBE_REQUEST, body.getBytes(UTF_8)).toBytes());
                    CastWire.ControlFrame reply = CastWire.ControlFrame.read(socket.getInputStream());
                    boolean accepted = reply != null
                            && reply.type == CastWire.STATUS_PROBE_RESPONSE
                            && statusTrue(reply.bodyText());
                    long elapsed = System.currentTimeMillis() - startedAt;
                    if (!accepted) {
                        callback.onError(CastErrors.VEHICLE_STATUS_REJECTED,
                                "probe reply type=" + (reply == null ? "null" : reply.type));
                        stop();
                        return;
                    }
                    JSONObject payload = payload("host", host);
                    put(payload, "elapsedMs", elapsed);
                    log("status-probe", payload);
                    callback.onPhase("handshaking", "");
                    callback.onProbed(host, elapsed);
                    openListeners(host);
                } catch (SocketTimeoutException error) {
                    callback.onError(CastErrors.VEHICLE_STATUS_UNAVAILABLE, "probe timeout");
                    stop();
                } catch (IOException error) {
                    callback.onError(CastErrors.VEHICLE_STATUS_UNAVAILABLE,
                            "probe: " + error.getClass().getSimpleName());
                    stop();
                } catch (SecurityException error) {
                    callback.onError(CastErrors.SECURITY_ERROR, "probe: " + error);
                    stop();
                } finally {
                    closeQuietly(socket);
                }
            }
        }, "zeeho-cast-probe");
        worker.setDaemon(true);
        addThread(worker);
        worker.start();
    }

    private static boolean statusTrue(String body) {
        try {
            return new JSONObject(body).optBoolean("status", false);
        } catch (Exception error) {
            return false;
        }
    }

    /**
     * 建一个“出口对”的 socket：找与目标同 /24 的 Network，用它的 SocketFactory。
     *
     * <p>没有这一步，Android 会按默认路由把 192.168.0.1 发到家用 Wi-Fi 或数据网络（r1.u2）。
     */
    private Socket newSocketBoundTo(String host) {
        try {
            InetAddress target = InetAddress.getByName(host);
            ConnectivityManager connectivityManager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                Network[] networks = connectivityManager.getAllNetworks();
                for (int i = 0; i < networks.length; i++) {
                    Network network = networks[i];
                    LinkProperties properties = connectivityManager.getLinkProperties(network);
                    if (properties == null) {
                        continue;
                    }
                    for (LinkAddress address : properties.getLinkAddresses()) {
                        if (sameSlash24(address.getAddress(), target)) {
                            return network.getSocketFactory().createSocket();
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // 回落到普通 socket
        } catch (RuntimeException ignored) {
        }
        return new Socket();
    }

    /** 前三个字节相同即认为同网段（IPv4）。 */
    private static boolean sameSlash24(InetAddress left, InetAddress right) {
        if (left == null || right == null) {
            return false;
        }
        byte[] a = left.getAddress();
        byte[] b = right.getAddress();
        if (a.length != 4 || b.length != 4) {
            return false;
        }
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    /* ================= 三个监听端口（CAST.md §3 步骤 4） ================= */

    private void openListeners(String expectedHost) {
        int[] ports = new int[]{CastWire.PORT_DATA, CastWire.PORT_MEDIA, CastWire.PORT_CONTROL};
        for (int i = 0; i < ports.length; i++) {
            final int port = ports[i];
            ServerSocket serverSocket;
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                // 绑 0.0.0.0：P2P 接口地址因机型而异，绑具体地址会漏
                serverSocket.bind(new InetSocketAddress(port));
                serverSocket.setSoTimeout(ACCEPT_TIMEOUT_MS);
            } catch (IOException error) {
                callback.onError(CastErrors.LISTENER_PORT_UNAVAILABLE, "bind " + port + ": " + error);
                stop();
                return;
            } catch (SecurityException error) {
                callback.onError(CastErrors.SECURITY_ERROR, "bind " + port + ": " + error);
                stop();
                return;
            }
            serverSockets.add(serverSocket);
            log("listener-ready", payload("port", port));
            Thread acceptThread = new Thread(new AcceptLoop(serverSocket, port, expectedHost),
                    "zeeho-cast-accept-" + port);
            acceptThread.setDaemon(true);
            addThread(acceptThread);
            acceptThread.start();
        }
        callback.onPhase("ready", "");
        callback.onListenersReady();
    }

    /** 每个端口的 accept 循环。 */
    private final class AcceptLoop implements Runnable {
        private final ServerSocket serverSocket;
        private final int port;
        private final String expectedHost;

        AcceptLoop(ServerSocket serverSocket, int port, String expectedHost) {
            this.serverSocket = serverSocket;
            this.port = port;
            this.expectedHost = expectedHost;
        }

        @Override
        public void run() {
            while (isActive()) {
                Socket socket;
                try {
                    socket = serverSocket.accept();
                } catch (SocketTimeoutException timeout) {
                    continue;
                } catch (IOException error) {
                    if (isActive()) {
                        callback.onError(CastErrors.LISTENER_PORT_UNAVAILABLE,
                                "accept " + port + ": " + error.getClass().getSimpleName());
                    }
                    return;
                } catch (SecurityException error) {
                    callback.onError(CastErrors.SECURITY_ERROR, "accept " + port + ": " + error);
                    return;
                }
                if (!isActive()) {
                    closeQuietly(socket);
                    return;
                }
                boolean fromGroupOwner = isFromGroupOwner(socket, expectedHost);
                JSONObject payload = payload("port", port);
                put(payload, "socketExpected", fromGroupOwner);
                put(payload, "socketAccepted", fromGroupOwner);
                if (!fromGroupOwner) {
                    // 同组设备也可能来连：只认 GO 地址（r1.G0）
                    log("socket-rejected", payload);
                    closeQuietly(socket);
                    continue;
                }
                log("socket-accepted", payload);
                try {
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(soTimeoutFor(port));
                } catch (IOException error) {
                    closeQuietly(socket);
                    continue;
                }
                synchronized (acceptedSockets) {
                    acceptedSockets.add(socket);
                }
                callback.onSocket(port, socket);
            }
        }
    }

    /** 各端口 accept 时的读超时（CAST.md §3.1；10920 在收到 113 后会切到 2 s）。 */
    public static int soTimeoutFor(int port) {
        if (port == CastWire.PORT_CONTROL) {
            return SO_TIMEOUT_CONTROL;
        }
        if (port == CastWire.PORT_DATA) {
            return SO_TIMEOUT_DATA_BEFORE_CAST;
        }
        return SO_TIMEOUT_MEDIA;
    }

    /** 来源校验：只接受 GO 地址；GO 地址未知时一律拒绝（r1.G0 的语义）。 */
    private boolean isFromGroupOwner(Socket socket, String expectedHost) {
        InetAddress remote = socket == null ? null : socket.getInetAddress();
        String host = expectedHost != null ? expectedHost : goAddress;
        if (remote == null || host == null || host.length() == 0) {
            return false;
        }
        return host.equals(remote.getHostAddress());
    }

    /* ================= 广播与收尾 ================= */

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleBroadcast(intent);
            }
        };
        if (Build.VERSION.SDK_INT >= 33 /* TIRAMISU */) {
            context.registerReceiver(receiver, filter, FLAG_RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    private void handleBroadcast(Intent intent) {
        if (intent == null || intent.getAction() == null || !isActive()) {
            return;
        }
        String action = intent.getAction();
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED);
            boolean enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
            log("p2p-state-changed", payload("wifiDirectEnabled", enabled));
            if (!enabled) {
                callback.onError(CastErrors.WIFI_DIRECT_DISABLED, "state disabled");
                stop();
            }
            return;
        }
        if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
            boolean connected = networkInfo != null && networkInfo.isConnected();
            log("p2p-connection-changed", payload("connected", connected));
            if (connected) {
                requestConnectionInfo();
            }
            return;
        }
        if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            requestPeers();
            return;
        }
        if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
            int discoveryState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE,
                    WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
            log("p2p-discovery-changed", payload("discoveryActive",
                    discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED));
        }
    }

    private void requestPeers() {
        if (!isActive()) {
            return;
        }
        try {
            manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
                @Override
                public void onPeersAvailable(WifiP2pDeviceList peers) {
                    onPeersAvailable(peers);
                }
            });
        } catch (SecurityException error) {
            callback.onError(CastErrors.WIFI_PERMISSION_DENIED, "requestPeers: " + error);
        } catch (RuntimeException error) {
            log("p2p-request-peers-failed", payload("message", String.valueOf(error)));
        }
    }

    private void scheduleTimeout() {
        cancelTimeout();
        timeoutTask = new Runnable() {
            @Override
            public void run() {
                if (isActive() && goAddress == null) {
                    callback.onError(CastErrors.P2P_TIMEOUT, "no group within " + P2P_TIMEOUT_MS + "ms");
                    stop();
                }
            }
        };
        mainHandler.postDelayed(timeoutTask, P2P_TIMEOUT_MS);
    }

    private void cancelTimeout() {
        if (timeoutTask != null) {
            mainHandler.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }
    }

    /** 会话是否还是当前这一轮。 */
    private boolean isActive() {
        return !stopped.get() && sessionSerial.get() == sessionId;
    }

    /* ================= 重连/降档用的局部收尾 ================= */

    /** 只关监听 socket，保留 P2P 组（降档重试时用：组还在，只需重新开监听）。 */
    public void closeListeners() {
        for (int i = 0; i < serverSockets.size(); i++) {
            closeQuietly(serverSockets.get(i));
        }
        serverSockets.clear();
    }

    /** 只关已接受的连接，保留监听与组。 */
    public void closeAcceptedSockets() {
        synchronized (acceptedSockets) {
            for (int i = 0; i < acceptedSockets.size(); i++) {
                closeQuietly(acceptedSockets.get(i));
            }
            acceptedSockets.clear();
        }
    }

    /**
     * 组还在的情况下重做一次探测并重开监听（r1.Q1 的重试路径）。
     *
     * @return false 表示当前没有可用的 GO 地址，调用方应走完整重启
     */
    public boolean reprobe() {
        String host = goAddress;
        if (host == null || host.length() == 0) {
            return false;
        }
        closeListeners();
        probe(host);
        return true;
    }

    /**
     * 全量收尾（幂等）。
     *
     * <p>关 socket 的顺序：先关监听（不再接新连接），再关已接的连接，最后拆 P2P 组。
     * 任何一步失败都继续往下走——收尾阶段抛异常只会把上层的重试逻辑带偏。
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        cancelTimeout();
        for (int i = 0; i < serverSockets.size(); i++) {
            closeQuietly(serverSockets.get(i));
        }
        serverSockets.clear();
        synchronized (acceptedSockets) {
            for (int i = 0; i < acceptedSockets.size(); i++) {
                closeQuietly(acceptedSockets.get(i));
            }
            acceptedSockets.clear();
        }
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // 没注册成功过
            } catch (RuntimeException ignored) {
            }
            receiver = null;
        }
        if (manager != null && channel != null) {
            try {
                manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onFailure(int reason) {
                    }
                });
            } catch (SecurityException ignored) {
            } catch (RuntimeException ignored) {
            }
        }
        channel = null;
        manager = null;
        goAddress = null;
        profile = null;
    }

    public String getGoAddress() {
        return goAddress;
    }

    public boolean isRunning() {
        return !stopped.get();
    }

    private void addThread(Thread thread) {
        synchronized (threads) {
            threads.add(thread);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void log(String event, JSONObject payload) {
        try {
            callback.onLog(event, payload == null ? new JSONObject() : payload);
        } catch (RuntimeException error) {
            Log.w(TAG, "callback threw: " + error);
        }
    }

    private static JSONObject payload(String key, Object value) {
        JSONObject json = new JSONObject();
        put(json, key, value);
        return json;
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (Exception ignored) {
        }
    }
}
