package io.github.lswlc33.zeehowebnext;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.util.Collections;

/**
 * 车辆附近检测：只监听目标蓝牙地址的 BLE 广播，取 RSSI/距离。
 *
 * **不连接车辆**：这里没有 connectGatt / 配对 / GATT 写命令，只 startScan 收广播。
 * 扫描只在 App 前台、用户在首页时进行；Activity 一退到后台就 stopScan。
 *
 * 距离是 RSSI 的粗略换算（log-distance path loss，n=2，默认 1m 处 -59dBm），
 * 只用于"在不在附近"的量级参考，不是精确测距。
 */
final class Proximity {

    private static final String TAG = "ZeehoWebNext";
    private static final long NEAR_MS = 20_000L;
    private static final long SEARCH_MS = 10_000L;
    private static final int DEFAULT_TX_POWER = -59;

    private static String address = "";
    private static BluetoothLeScanner scanner;
    private static ScanCallback callback;
    private static boolean scanning;
    private static boolean unavailable;
    private static long startedAt;
    private static long lastSeen;
    private static long updatedAt;
    private static int rssi;
    private static double meters;

    private Proximity() {
    }

    static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT >= 31) {
            // 读扫描结果的 BluetoothDevice 地址也需要 CONNECT；这里只申请/使用权限，
            // 代码里没有任何 connectGatt / 配对调用。
            return c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    }

    static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[] {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
    }

    static String start(Context context, String mac) {
        String a = normalize(mac);
        if (a.isEmpty()) {
            address = "";
            stopScan();
            return status(context);
        }
        if (!a.equals(address)) {
            address = a;
            lastSeen = 0;
            rssi = 0;
            meters = 0;
            if (scanning) stopScan();
        }
        if (!hasPermission(context)) {
            stopScan();
            return status(context);
        }
        if (scanning) return status(context);
        unavailable = false;
        if (Build.VERSION.SDK_INT < 21) {
            unavailable = true;
            return status(context);
        }
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
            if (scanner == null) {
                unavailable = true;
                return status(context);
            }
            ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(address).build();
            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();
            callback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    if (result == null || result.getDevice() == null) return;
                    if (!address.equalsIgnoreCase(result.getDevice().getAddress())) return;
                    int next = result.getRssi();
                    rssi = rssi == 0 ? next : Math.round(rssi * 0.6f + next * 0.4f);
                    int tx = result.getTxPower();
                    if (tx == ScanResult.TX_POWER_NOT_PRESENT) tx = DEFAULT_TX_POWER;
                    double d = Math.pow(10.0, (tx - rssi) / 20.0);
                    meters = Math.max(0.5, Math.min(100.0, d));
                    lastSeen = System.currentTimeMillis();
                    updatedAt = lastSeen;
                }

                @Override
                public void onScanFailed(int errorCode) {
                    Log.w(TAG, "BLE scan failed: " + errorCode);
                    scanning = false;
                }
            };
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            scanning = true;
            startedAt = System.currentTimeMillis();
            updatedAt = startedAt;
            Log.i(TAG, "BLE proximity scan started");
        } catch (Exception e) {
            Log.w(TAG, "BLE proximity start failed", e);
            scanning = false;
            unavailable = true;
        }
        return status(context);
    }

    static void stop(Context context) {
        stopScan();
        updatedAt = System.currentTimeMillis();
    }

    /** Activity 回前台：如果页面之前给过蓝牙地址且权限在，继续扫描。 */
    static void resume(Context context) {
        if (!address.isEmpty() && hasPermission(context)) start(context, address);
    }

    static String status(Context context) {
        JSONObject j = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            String state;
            if (address.isEmpty()) state = "noaddr";
            else if (context == null || !hasPermission(context)) state = "needperm";
            else if (unavailable) state = "unavailable";
            else if (lastSeen > 0 && now - lastSeen <= NEAR_MS) state = "nearby";
            else if (scanning && now - startedAt <= SEARCH_MS) state = "searching";
            else state = "far";
            j.put("ok", true);
            j.put("state", state);
            j.put("rssi", rssi);
            j.put("meters", Math.round(meters * 10) / 10.0);
            j.put("updatedAt", updatedAt);
            j.put("lastSeen", lastSeen);
        } catch (Exception ignored) {
        }
        return j.toString();
    }

    private static void stopScan() {
        try {
            if (scanner != null && callback != null) scanner.stopScan(callback);
        } catch (Exception ignored) {
        }
        scanner = null;
        callback = null;
        scanning = false;
    }

    private static String normalize(String mac) {
        if (mac == null) return "";
        String s = mac.trim().toUpperCase().replace('-', ':');
        return s.matches("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$") ? s : "";
    }
}
