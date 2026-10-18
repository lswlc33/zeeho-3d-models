package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * 车图接口只给经纬度，官方微件上的地点名是反地理编码出来的。
 *
 * Geocoder 是阻塞调用，只在 Poller 的后台线程里用；按 100 米网格缓存 30 分钟，
 * 设备没有 Geocoder（部分模拟器/无 GMS 环境）时直接返回空，微件隐藏地点行。
 */
final class Geo {

    private static final String TAG = "ZeehoWebNext";
    private static final String SP = "webnext_widget";
    private static final long TTL = 30 * 60 * 1000L;

    private Geo() {
    }

    static String resolve(Context c, double lat, double lng) {
        if (Double.isNaN(lat) || Double.isNaN(lng)) return "";
        SharedPreferences p = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        String key = String.format(Locale.US, "%.3f,%.3f", lat, lng);
        String cachedKey = p.getString("geo_key", "");
        String cached = p.getString("geo_addr", "");
        long at = p.getLong("geo_at", 0L);
        if (key.equals(cachedKey) && !cached.isEmpty() && System.currentTimeMillis() - at < TTL) {
            return cached;
        }
        if (!Geocoder.isPresent()) return cachedKey.equals(key) ? cached : "";
        try {
            List<Address> list = new Geocoder(c, Locale.CHINA).getFromLocation(lat, lng, 1);
            if (list == null || list.isEmpty()) return cachedKey.equals(key) ? cached : "";
            Address a = list.get(0);
            String name = first(a.getFeatureName(), a.getSubLocality(),
                a.getThoroughfare(), a.getLocality());
            if (name.isEmpty()) return cachedKey.equals(key) ? cached : "";
            p.edit().putString("geo_key", key).putString("geo_addr", name)
                .putLong("geo_at", System.currentTimeMillis()).apply();
            return name;
        } catch (Exception e) {
            Log.w(TAG, "geocode failed", e);
            return cachedKey.equals(key) ? cached : "";
        }
    }

    private static String first(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }
}
