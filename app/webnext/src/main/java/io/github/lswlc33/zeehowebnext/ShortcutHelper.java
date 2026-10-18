package io.github.lswlc33.zeehowebnext;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态 App Shortcuts：解锁 / 锁车 / 鸣笛 / 打开坐垫。
 *
 * Intent 里带一个本机随机 secret，ActionActivity 校验后才执行。这样即使 Activity 是 exported
 * （桌面快捷方式需要），别的应用只知道包名也发不动车控；secret 只在本应用私有 prefs 和桌面
 * 保存的 shortcut intent 里。
 */
final class ShortcutHelper {

    /** 快捷方式定义变了就 +1，强制重注册一次。 */
    private static final int VERSION = 1;
    private static final String SP = "webnext";
    private static final String K_SIG = "shortcut_sig";
    /** 同一进程里 Application 与 onResume 都会调，第二次直接返回。 */
    private static volatile boolean done;

    private ShortcutHelper() {
    }

    static void ensure(Context context) {
        if (Build.VERSION.SDK_INT < 25 || done) return;
        done = true;
        Context c = context.getApplicationContext();
        ShortcutManager sm = (ShortcutManager) c.getSystemService(Context.SHORTCUT_SERVICE);
        if (sm == null) return;
        String secret = NativeStore.shortcutSecret(c);
        android.content.SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        String sig = VERSION + ":" + secret;
        // 快捷方式会一直留在桌面；每次启动重新 setDynamicShortcuts 是一次没必要的 binder + 图标构建。
        try {
            if (sig.equals(sp.getString(K_SIG, "")) && sm.getDynamicShortcuts().size() >= 4) return;
        } catch (Exception ignored) {
            // 查询失败就继续重设
        }
        List<ShortcutInfo> list = new ArrayList<>();
        add(c, list, "unlock", R.string.shortcut_unlock, R.drawable.ic_shortcut_unlock, "unlock", secret, 1);
        add(c, list, "lock", R.string.shortcut_lock, R.drawable.ic_shortcut_lock, "lock", secret, 2);
        add(c, list, "horn", R.string.shortcut_horn, R.drawable.ic_shortcut_horn, "horn", secret, 3);
        add(c, list, "seat", R.string.shortcut_seat, R.drawable.ic_shortcut_seat, "seat", secret, 4);
        try {
            sm.setDynamicShortcuts(list);
            sp.edit().putString(K_SIG, sig).apply();
        } catch (Exception ignored) {
            // 桌面不支持动态快捷方式 / 被限流：不影响 App 其它功能
        }
    }

    private static void add(Context c, List<ShortcutInfo> out, String id, int label,
                            int icon, String action, String secret, int rank) {
        Intent i = new Intent(c, ActionActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(ActionActivity.EXTRA_ACTION, action)
            .putExtra(ActionActivity.EXTRA_SECRET, secret);
        out.add(new ShortcutInfo.Builder(c, id)
            .setShortLabel(c.getString(label))
            .setLongLabel(c.getString(label))
            .setRank(rank)
            .setIcon(Icon.createWithResource(c, icon))
            .setIntent(i)
            .build());
    }
}
