package io.github.lswlc33.zeehowebnext;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * APK 自更新。
 *
 * 远端清单固定是 https://lswlc33.github.io/zeeho-3d-models/app/version.json，
 * 读取 versionCode/versionName/url/notes；只有远端 versionCode 大于当前安装包才提示。
 *
 * 下载后先做三件事再交给安装器：
 *   1. 包名必须是 io.github.lswlc33.zeehowebnext；
 *   2. versionCode 必须大于当前版本；
 *   3. 签名证书集合必须与当前安装包一致（防止下载源被替换成别的签名包）。
 * 安装走自己的只读 ContentProvider（工程没有 AndroidX，不能用 FileProvider）。
 */
final class UpdateChecker {

    private static final String TAG = "ZeehoWebNext";
    private static final String MANIFEST_URL =
        "https://lswlc33.github.io/zeeho-3d-models/app/version.json";
    private static final String APK_URL =
        "https://lswlc33.github.io/zeeho-3d-models/app/zeehowebnext.apk";
    private static final String APK_HOST = "lswlc33.github.io";
    private static final String APK_PATH = "/zeeho-3d-models/app/";
    private static final String MIME_APK = "application/vnd.android.package-archive";

    private static boolean checked;
    private static boolean dialogShowing;
    private static boolean downloading;
    private static volatile File pendingApk;

    private UpdateChecker() {
    }

    /** 每次 onResume 调用：从"允许安装未知应用"设置页回来后继续安装。 */
    static void onResume(Activity activity) {
        File apk = pendingApk;
        if (apk == null || !apk.isFile()) return;
        if (canInstall(activity)) {
            pendingApk = null;
            install(activity, apk);
        }
    }

    /** 每次进程只检查一次；网络失败保持静默，不影响页面启动。 */
    static void check(Activity activity) {
        if (checked) return;
        checked = true;
        final boolean debug = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        final String manifest = manifestUrl(activity, debug);
        final String currentName = versionName(activity);
        final long currentCode = currentVersionCode(activity);
        new Thread(() -> {
            try {
                String body = fetchText(manifest);
                JSONObject j = new JSONObject(body);
                long code = j.optLong("versionCode", -1);
                String name = j.optString("versionName", "");
                String url = j.optString("url", APK_URL);
                String notes = j.optString("notes", "");
                if (code <= currentCode || name.isEmpty()) return;
                if (!allowedApkUrl(url, debug)) {
                    Log.w(TAG, "update url rejected: " + url);
                    return;
                }
                final long remoteCode = code;
                final String remoteName = name;
                final String remoteUrl = url;
                final String remoteNotes = notes;
                activity.runOnUiThread(() -> showDialog(activity, currentName, currentCode,
                    remoteName, remoteCode, remoteUrl, remoteNotes));
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
            }
        }, "zeeho-update-check").start();
    }

    private static void showDialog(Activity activity, String currentName, long currentCode,
                                   String remoteName, long remoteCode, String url, String notes) {
        if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())
            || dialogShowing) {
            return;
        }
        dialogShowing = true;
        String message = activity.getString(R.string.update_message,
            currentName + " (" + currentCode + ")",
            remoteName + " (" + remoteCode + ")",
            notes.isEmpty() ? activity.getString(R.string.update_no_notes) : notes);
        AlertDialog d = new AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, remoteName))
            .setMessage(message)
            .setPositiveButton(R.string.update_now, (dialog, which) -> download(activity, url))
            .setNegativeButton(R.string.update_later, null)
            .create();
        d.setOnDismissListener(x -> dialogShowing = false);
        d.show();
    }

    private static void download(Activity activity, String url) {
        if (downloading) return;
        downloading = true;
        File dir = new File(activity.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            downloading = false;
            toast(activity, activity.getString(R.string.update_download_failed, "无法创建缓存目录"));
            return;
        }
        File part = new File(dir, "zeehowebnext.apk.part");
        File apk = UpdateProvider.apkFile(activity);
        part.delete();
        apk.delete();

        ProgressDialog pd = new ProgressDialog(activity);
        pd.setTitle(R.string.update_downloading);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            String err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "ZeehoWebNext/" + versionName(activity));
                c.setRequestProperty("Cache-Control", "no-cache");
                int status = c.getResponseCode();
                if (status != 200) throw new Exception("HTTP " + status);
                long total = c.getContentLengthLong();
                if (total > 30L * 1024 * 1024) throw new Exception("安装包异常大");
                if (total > 0) {
                    activity.runOnUiThread(() -> {
                        pd.setIndeterminate(false);
                        pd.setMax((int) (total / 1024));
                    });
                }
                long done = 0;
                long lastUi = 0;
                try (InputStream in = c.getInputStream();
                     FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (done > 30L * 1024 * 1024) throw new Exception("安装包异常大");
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 120) {
                            lastUi = now;
                            final long d = done;
                            final long t = total;
                            activity.runOnUiThread(() -> {
                                if (t > 0) pd.setProgress((int) (d / 1024));
                                else pd.setMessage(activity.getString(R.string.update_download_size,
                                    d / 1024 / 1024));
                            });
                        }
                    }
                    out.flush();
                }
                if (!part.renameTo(apk)) throw new Exception("保存安装包失败");
                String verify = verify(activity, apk);
                if (verify != null) {
                    apk.delete();
                    err = verify;
                }
            } catch (Exception e) {
                part.delete();
                apk.delete();
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Log.w(TAG, "update download failed", e);
            }
            final String error = err;
            activity.runOnUiThread(() -> {
                try {
                    pd.dismiss();
                } catch (Exception ignored) {
                }
                downloading = false;
                if (error != null) {
                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.update_failed)
                        .setMessage(error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                } else {
                    install(activity, apk);
                }
            });
        }, "zeeho-update-download").start();
    }

    private static String verify(Activity activity, File apk) {
        try {
            PackageManager pm = activity.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
            PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (info == null) return activity.getString(R.string.update_verify_failed);
            if (!activity.getPackageName().equals(info.packageName)) {
                return activity.getString(R.string.update_verify_failed);
            }
            long current = currentVersionCode(activity);
            if (versionCode(info) <= current) {
                return activity.getString(R.string.update_verify_failed);
            }
            PackageInfo cur = pm.getPackageInfo(activity.getPackageName(), flags);
            Set<String> a = signerHashes(info);
            Set<String> b = signerHashes(cur);
            if (a.isEmpty() || !a.equals(b)) {
                return activity.getString(R.string.update_signature_failed);
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "update verify failed", e);
            return activity.getString(R.string.update_verify_failed);
        }
    }

    private static void install(Activity activity, File apk) {
        if (!apk.isFile()) return;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            toast(activity, activity.getString(R.string.update_allow_install));
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            } catch (Exception e) {
                toast(activity, activity.getString(R.string.update_install_failed));
            }
            return;
        }
        pendingApk = null;
        try {
            Uri uri = UpdateProvider.uriFor(activity);
            Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME_APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setClipData(ClipData.newRawUri("", uri));
            activity.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "open installer failed", e);
            toast(activity, activity.getString(R.string.update_install_failed));
        }
    }

    private static boolean canInstall(Activity activity) {
        return Build.VERSION.SDK_INT < 26
            || activity.getPackageManager().canRequestPackageInstalls();
    }

    private static String manifestUrl(Activity activity, boolean debug) {
        if (debug) {
            Intent i = activity.getIntent();
            String override = i == null ? null : i.getStringExtra("update_manifest");
            if (override != null
                && (override.startsWith("http://") || override.startsWith("https://"))) {
                return override;
            }
        }
        return MANIFEST_URL + "?_=" + System.currentTimeMillis();
    }

    private static boolean allowedApkUrl(String url, boolean debug) {
        try {
            URL u = new URL(url);
            if (debug) return "http".equals(u.getProtocol()) || "https".equals(u.getProtocol());
            return "https".equals(u.getProtocol())
                && APK_HOST.equalsIgnoreCase(u.getHost())
                && (u.getPath() == null ? "" : u.getPath()).startsWith(APK_PATH);
        } catch (Exception e) {
            return false;
        }
    }

    private static String fetchText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("User-Agent", "ZeehoWebNext");
            int status = c.getResponseCode();
            if (status != 200) throw new Exception("HTTP " + status);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 128 * 1024) throw new Exception("manifest too large");
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static Set<String> signerHashes(PackageInfo info) {
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            sigs = info.signingInfo.getApkContentsSigners();
        } else {
            sigs = info.signatures;
        }
        Set<String> out = new HashSet<>();
        if (sigs == null) return out;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : sigs) {
                byte[] d = md.digest(s.toByteArray());
                StringBuilder sb = new StringBuilder(d.length * 2);
                for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
                out.add(sb.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "signer hash failed", e);
        }
        return out;
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static long currentVersionCode(Activity activity) {
        try {
            return versionCode(activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String versionName(Activity activity) {
        try {
            String v = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0).versionName;
            return v == null ? "0" : v;
        } catch (Exception e) {
            return "0";
        }
    }

    private static void toast(Activity activity, String text) {
        activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_LONG).show());
    }
}
