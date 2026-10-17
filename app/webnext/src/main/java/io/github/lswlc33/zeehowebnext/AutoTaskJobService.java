package io.github.lswlc33.zeehowebnext;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/** 每日自动任务的执行载体。网络不可用时由 JobScheduler 等网络恢复再跑。 */
public final class AutoTaskJobService extends JobService {

    private static final String TAG = "ZeehoWebNext";

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                if (NativeStore.autoEnabled(this)) {
                    AutoTaskRunner.Result r = AutoTaskRunner.run(this);
                    NativeStore.setAutoResult(this, System.currentTimeMillis(), r.message);
                    Notify.autoTask(this, r.ok, r.message);
                    Log.i(TAG, "auto task: ok=" + r.ok + " " + r.message);
                }
            } catch (Exception e) {
                Log.w(TAG, "auto task failed", e);
                Notify.autoTask(this, false, "执行异常：" + e.getClass().getSimpleName());
            } finally {
                // 先 jobFinished 再排下一天：当前 Job 还没结束时 cancel 同一个 id 可能把
                // 正在跑的自己也停掉（onStopJob），排程会打架。
                jobFinished(params, false);
                AutoTaskScheduler.schedule(this);
            }
        }, "auto-task").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
