package io.github.lswlc33.zeehowebnext;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * App Shortcut / 小组件按钮的透明执行页：发一条车控指令，显示一句结果后自动关闭。
 *
 * 安全性：只有携带本机随机 secret 的 Intent 才执行（见 ShortcutHelper），否则退回主界面。
 */
public final class ActionActivity extends Activity {

    static final String EXTRA_ACTION = "shell_action";
    static final String EXTRA_SECRET = "shell_secret";

    private TextView message;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String action = getIntent().getStringExtra(EXTRA_ACTION);
        String secret = getIntent().getStringExtra(EXTRA_SECRET);
        if (action == null || secret == null
            || !secret.equals(NativeStore.shortcutSecret(this))) {
            openMain();
            finish();
            return;
        }
        if (!NativeStore.isConfigured(this)) {
            Toast.makeText(this, R.string.action_need_login, Toast.LENGTH_LONG).show();
            openMain();
            finish();
            return;
        }
        setContentView(buildView(actionLabel(action)));
        new Thread(() -> {
            ApiClient.Result r = run(action);
            runOnUiThread(() -> showResult(r));
        }, "shortcut-action").start();
    }

    private View buildView(String label) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        int p = dp(22);
        card.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0141218);
        bg.setCornerRadius(dp(24));
        card.setBackground(bg);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(0xFFE6E0E9);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(28), dp(28));
        pp.topMargin = dp(12);
        card.addView(progress, pp);

        message = new TextView(this);
        message.setText(R.string.action_sending);
        message.setTextColor(0xFFCAC4D0);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(10);
        card.addView(message, mp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(card, lp);
        return root;
    }

    private void showResult(ApiClient.Result r) {
        boolean ok = r != null && r.ok;
        if (progress != null) progress.setVisibility(View.GONE);
        if (message != null) {
            message.setText(ok ? getString(R.string.action_sent)
                : getString(R.string.action_failed, ApiClient.message(r)));
            message.setTextColor(ok ? 0xFF8CE99A : 0xFFFF8A80);
        }
        Toast.makeText(this, message != null ? message.getText() : getString(R.string.action_failed, ""),
            Toast.LENGTH_SHORT).show();
        Notify.action(this, ok, message != null ? message.getText().toString() : "");
        if (r != null && r.auth) NativeStore.setAuthFailed(this, true);
        message.postDelayed(this::finish, 1100);
    }

    private ApiClient.Result run(String action) {
        ApiClient api = new ApiClient(NativeStore.token(this));
        String vin = NativeStore.vin(this);
        String a = action == null ? "" : action;
        if ("unlock".equals(a)) return api.lock(vin, true);
        if ("lock".equals(a)) return api.lock(vin, false);
        if ("horn".equals(a)) return api.horn(vin);
        if ("seat".equals(a)) return api.openSeat(vin);
        ApiClient.Result r = new ApiClient.Result();
        r.message = "未知操作";
        return r;
    }

    private String actionLabel(String action) {
        if ("unlock".equals(action)) return getString(R.string.shortcut_unlock);
        if ("lock".equals(action)) return getString(R.string.shortcut_lock);
        if ("horn".equals(action)) return getString(R.string.shortcut_horn);
        if ("seat".equals(action)) return getString(R.string.shortcut_seat);
        return getString(R.string.app_name);
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
