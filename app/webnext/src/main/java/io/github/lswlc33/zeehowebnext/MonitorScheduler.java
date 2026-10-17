package io.github.lswlc33.zeehowebnext;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/** 后台轮询的周期任务（15 分钟）。前台服务负责实时 60 秒；这里是杀掉/重启后的兜底。 */
final class MonitorScheduler {

    private static final int JOB = 2101;
    private static final String TAG = "ZeehoWebNext";

    private MonitorScheduler() {
    }

    static void ensure(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!NativeStore.backgroundEnabled(c) || NativeStore.token(c).isEmpty()) {
            Log.d(TAG, "monitor ensure disabled bg=" + NativeStore.backgroundEnabled(c)
                + " token=" + !NativeStore.token(c).isEmpty());
            js.cancel(JOB);
            return;
        }
        if (js.getPendingJob(JOB) != null) {
            Log.d(TAG, "monitor ensure pending");
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB, new ComponentName(c, MonitorJobService.class))
            .setPeriodic(15 * 60 * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build();
        try {
            int r = js.schedule(job);
            Log.i(TAG, "monitor schedule result=" + r);
        } catch (Exception e) {
            Log.w(TAG, "monitor schedule failed", e);
        }
    }

    static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB);
    }
}
