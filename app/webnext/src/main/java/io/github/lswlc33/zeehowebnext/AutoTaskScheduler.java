package io.github.lswlc33.zeehowebnext;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.Calendar;

/** 每天指定时间跑一次自动任务。用一次性 Job + persisted，跑完自己排下一天。 */
final class AutoTaskScheduler {

    private static final int JOB = 2102;
    private static final String TAG = "ZeehoWebNext";

    private AutoTaskScheduler() {
    }

    static void ensure(Context c) {
        if (!NativeStore.autoEnabled(c)) {
            cancel(c);
            return;
        }
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        boolean pending = js.getPendingJob(JOB) != null;
        Log.d(TAG, "auto ensure enabled pending=" + pending);
        if (!pending) schedule(c);
    }

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.cancel(JOB);
        if (!NativeStore.autoEnabled(c)) return;
        long delay = nextDelay(NativeStore.autoTime(c));
        JobInfo job = new JobInfo.Builder(JOB, new ComponentName(c, AutoTaskJobService.class))
            .setMinimumLatency(delay)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build();
        try {
            int r = js.schedule(job);
            Log.i(TAG, "auto schedule delay=" + delay + " result=" + r);
        } catch (Exception e) {
            Log.w(TAG, "auto schedule failed delay=" + delay, e);
        }
    }

    static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB);
    }

    /**
     * 失败后的短延迟重试（默认 20 分钟）。不能直接排到明天：断网/服务端抖动一次
     * 就会丢掉当天签到；也不能让系统的 onStopJob 重试和这里的 schedule 互相 cancel。
     */
    static void scheduleRetry(Context c, long delayMs) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.cancel(JOB);
        if (!NativeStore.autoEnabled(c)) return;
        JobInfo job = new JobInfo.Builder(JOB, new ComponentName(c, AutoTaskJobService.class))
            .setMinimumLatency(Math.max(60_000L, delayMs))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build();
        try {
            int r = js.schedule(job);
            Log.i(TAG, "auto retry in " + (delayMs / 60_000L) + " min, result=" + r);
        } catch (Exception e) {
            Log.w(TAG, "auto retry schedule failed", e);
        }
    }

    /** 下一次 HH:mm 的毫秒延迟；今天已过就排到明天。 */
    static long nextDelay(String hhmm) {
        int h = 9, m = 0;
        try {
            String[] a = hhmm.split(":");
            h = Integer.parseInt(a[0]);
            m = Integer.parseInt(a[1]);
        } catch (Exception ignored) {
        }
        Calendar now = Calendar.getInstance();
        Calendar at = (Calendar) now.clone();
        at.set(Calendar.HOUR_OF_DAY, Math.max(0, Math.min(23, h)));
        at.set(Calendar.MINUTE, Math.max(0, Math.min(59, m)));
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (!at.after(now)) at.add(Calendar.DAY_OF_YEAR, 1);
        return Math.max(1000L, at.getTimeInMillis() - now.getTimeInMillis());
    }
}
