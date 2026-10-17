package io.github.lswlc33.zeehowebnext;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Daily task runner，逻辑对齐 public/js/tasks.js 的「一键完成」：
 * 签到（h5）→ 未完成的 1023 发帖 / 1026 点赞 / 1024 分享 → 删掉本次自己发的动态。
 *
 * 只做有真实完成接口的三项；被动任务不假装完成。每一步之间按用户设置的 gap 停一下，避免风控。
 */
final class AutoTaskRunner {

    static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private AutoTaskRunner() {
    }

    static Result run(Context context) {
        Context c = context.getApplicationContext();
        String token = NativeStore.token(c);
        String uid = NativeStore.uid(c);
        if (token.isEmpty()) return new Result(false, "未登录，自动任务未执行");
        if (uid.isEmpty()) return new Result(false, "没有账号 ID，自动任务未执行（在「我的」页填写）");

        ApiClient api = new ApiClient(token);
        StringBuilder notes = new StringBuilder();
        boolean signed = false;

        ApiClient.Result count = api.signinCount();
        if (count.auth) {
            NativeStore.setAuthFailed(c, true);
            return new Result(false, "登录态已失效，请打开 App 重新登录");
        }
        boolean already = count.ok && NumberText.isOne(ApiClient.text(ApiClient.obj(count), "signStatus"));
        if (already) {
            signed = true;
            notes.append("签到已签");
        } else {
            ApiClient.Result s = api.signin();
            if (s.ok) {
                signed = true;
                notes.append("签到成功");
            } else {
                notes.append("签到失败：").append(ApiClient.message(s));
            }
        }

        ApiClient.Result tasks = api.integralTasks(uid);
        if (tasks.auth) {
            NativeStore.setAuthFailed(c, true);
            return new Result(false, "登录态已失效，请打开 App 重新登录");
        }
        if (!tasks.ok) {
            return new Result(false, "任务列表读取失败：" + ApiClient.message(tasks));
        }
        JSONObject td = ApiClient.obj(tasks);
        JSONArray list = td == null ? null : td.optJSONArray("ps0003_01_002_VOList");
        if (list == null) list = new JSONArray();

        String content = NativeStore.autoContent(c);
        int gap = NativeStore.autoGap(c);
        int done = 0;
        int failed = 0;
        String lastPostId = "";
        boolean published = false;
        boolean fatal = false;

        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String code = ApiClient.text(o, "code");
            String action = ApiClient.actionFor(code);
            if (action.isEmpty()) continue;
            if ("0".equals(ApiClient.text(o, "completeFlag"))) continue;

            if ("post".equals(action)) {
                String id = publish(api, uid, content, notes);
                if (id.isEmpty()) {
                    failed++;
                } else {
                    lastPostId = id;
                    published = true;
                    done++;
                }
            } else {
                String id = lastPostId;
                if (id.isEmpty()) id = ApiClient.firstPostId(api.postMine(uid));
                if (id.isEmpty()) {
                    id = publish(api, uid, content, notes);
                    if (!id.isEmpty()) {
                        lastPostId = id;
                        published = true;
                    }
                }
                if (id.isEmpty()) {
                    failed++;
                } else if ("like".equals(action)) {
                    ApiClient.Result r = api.like(id);
                    if (r.auth) {
                        NativeStore.setAuthFailed(c, true);
                        fatal = true;
                        notes.append("；登录态失效");
                        break;
                    }
                    if (r.ok) done++;
                    else {
                        failed++;
                        notes.append("；点赞失败：").append(ApiClient.message(r));
                    }
                } else {
                    ApiClient.Result r = api.share(id);
                    if (r.auth) {
                        NativeStore.setAuthFailed(c, true);
                        fatal = true;
                        notes.append("；登录态失效");
                        break;
                    }
                    if (r.ok) {
                        done++;
                        api.adjustShare();
                    } else {
                        failed++;
                        notes.append("；分享失败：").append(ApiClient.message(r));
                    }
                }
            }
            sleep(gap);
        }

        if (published && !lastPostId.isEmpty() && NativeStore.autoDelete(c)) {
            ApiClient.Result d = api.deletePost(lastPostId);
            if (!d.ok) notes.append("；删除动态失败");
        }

        String msg = notes + " · 完成 " + done + " 项";
        if (failed > 0) msg += "，失败 " + failed + " 项";
        return new Result(!fatal && failed == 0, msg);
    }

    private static String publish(ApiClient api, String uid, String content, StringBuilder notes) {
        ApiClient.Result p = api.publish(content);
        if (!p.ok) {
            notes.append("；发帖失败：").append(ApiClient.message(p));
            return "";
        }
        String id = ApiClient.firstPostId(api.postMine(uid));
        if (id.isEmpty()) notes.append("；发帖成功但取不到动态 ID");
        return id;
    }

    private static void sleep(int ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class NumberText {
        static boolean isOne(String s) {
            return "1".equals(s) || "true".equalsIgnoreCase(s);
        }
    }
}
