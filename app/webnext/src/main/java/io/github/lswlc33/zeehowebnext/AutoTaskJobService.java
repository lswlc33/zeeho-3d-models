package io.github.lswlc33.zeehowebnext;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/** 每日自动任务的执行载体。网络不可用时由 JobScheduler 等网络恢复再跑。 */
public final class AutoTaskJobService extends JobService {

    private static final String TAG = "ZeehoWebNext";
    /** 失败重试：20 分钟一次，最多 3 次；都失败才排到明天（以前一次失败就丢当天）。 */
    private static final int MAX_TRIES = 3;
    private static final long RETRY_MS = 20 * 60_000L;

    /** onStopJob 已经让系统接管重试时，收尾线程不许再 schedule（会把系统重试 cancel 掉）。 */
    private volatile boolean stopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped = false;
        new Thread(() -> {
            boolean ok = false;
            try {
                if (NativeStore.autoEnabled(this)) {
                    AutoTaskRunner.Result r = AutoTaskRunner.run(this);
                    NativeStore.setAutoResult(this, System.currentTimeMillis(), r.message);
                    Notify.autoTask(this, r.ok, r.message);
                    Log.i(TAG, "auto task: ok=" + r.ok + " " + r.message);
                    ok = r.ok;
                } else {
                    // 用户在跑到一半时关掉了自动任务：不再重试，schedule() 自己会 cancel。
                    ok = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "auto task failed", e);
                Notify.autoTask(this, false, "执行异常：" + e.getClass().getSimpleName());
            } finally {
                // 先 jobFinished 再排下一次：当前 Job 还没结束时 cancel 同一个 id 可能把
                // 正在跑的自己也停掉（onStopJob），排程会打架。
                jobFinished(params, false);
                if (stopped) return;                    // 系统会按 onStopJob 的 true 自己重试
                if (ok) {
                    NativeStore.setAutoTry(this, 0);
                    AutoTaskScheduler.schedule(this);   // 排到明天同一时刻
                } else {
                    int n = NativeStore.autoTry(this) + 1;
                    NativeStore.setAutoTry(this, n);
                    if (n < MAX_TRIES) {
                        AutoTaskScheduler.scheduleRetry(this, RETRY_MS);
                    } else {
                        NativeStore.setAutoTry(this, 0);
                        AutoTaskScheduler.schedule(this);   // 今天放弃，明天再来
                    }
                }
            }
        }, "auto-task").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;
    }
}
