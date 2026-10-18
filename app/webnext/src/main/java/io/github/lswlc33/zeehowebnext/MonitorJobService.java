package io.github.lswlc33.zeehowebnext;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/** 15 分钟周期兜底轮询：不需要前台服务，Android 重启后 JobScheduler 会保留。 */
public final class MonitorJobService extends JobService {

    private static final String TAG = "ZeehoWebNext";

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                Poller.poll(this);
                // 胎压不进 60 秒轮询：跟着 15 分钟的兜底任务查一次就够（用户开了提醒才发请求）。
                Alerts.checkTire(this);
            } catch (Exception e) {
                Log.w(TAG, "monitor job poll failed", e);
            } finally {
                jobFinished(params, false);
            }
        }, "monitor-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
