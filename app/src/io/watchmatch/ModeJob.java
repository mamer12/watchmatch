package io.watchmatch;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

/**
 * Runs HomeMode's long ADB jobs in the main process. The UI lives in :home, so going through the job system is what
 * keeps this process alive until the work ends.
 */
public class ModeJob extends JobService {
    static final String APPLY = "apply", VERIFY = "verify", REVERT = "revert", MEMORY = "memory", REMOTE = "remote";
    private static final String ACTION = "action", MODE = "mode";
    private static final int ID_BASE = 1000;
    private static final long DEADLINE_SLACK_MS = 2000;

    /** One pending job per action: a newer request replaces an older one of the same action. */
    static int jobId(String action) {
        return ID_BASE + Math.abs(action.hashCode() % 1000);
    }

    static void schedule(Context c, String action, String mode, long delayMs) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString(ACTION, action);
        extras.putString(MODE, mode == null ? "" : mode);
        JobInfo.Builder job = new JobInfo.Builder(jobId(action), new ComponentName(c, ModeJob.class))
                .setExtras(extras)
                .setPersisted(false)
                .setOverrideDeadline(delayMs + DEADLINE_SLACK_MS);
        if (delayMs > 0) job.setMinimumLatency(delayMs);
        js.schedule(job.build());
    }

    @Override public boolean onStartJob(final JobParameters params) {
        final String action = params.getExtras().getString(ACTION, "");
        final String mode = params.getExtras().getString(MODE, "");
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                switch (action) {
                    case APPLY: HomeMode.applyNow(app, mode); break;
                    case VERIFY: HomeMode.verifyNow(app); break;
                    case REVERT: HomeMode.revertHomeNow(app); break;
                    case MEMORY: HomeMode.measureMemoryNow(app, mode); break;
                    case REMOTE: HomeMode.ensureRemoteServiceNow(app); break;
                    default: break;
                }
            } catch (Throwable t) {
                AppLog.w("job " + action + " failed: " + t);
            } finally {
                jobFinished(params, false);
            }
        }, "watchmatch-" + action).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
