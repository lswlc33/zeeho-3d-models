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
