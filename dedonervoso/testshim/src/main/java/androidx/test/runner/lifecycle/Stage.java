package androidx.test.runner.lifecycle;

/** Activity lifecycle stages reported to {@link ActivityLifecycleMonitor}. */
public enum Stage {
    PRE_ON_CREATE, CREATED, STARTED, RESUMED, PAUSED, STOPPED, RESTARTED, DESTROYED
}
