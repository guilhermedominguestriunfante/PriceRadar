package androidx.test.runner.lifecycle;

public final class ActivityLifecycleMonitorRegistry {
    private static volatile ActivityLifecycleMonitor instance;

    private ActivityLifecycleMonitorRegistry() {}

    public static ActivityLifecycleMonitor getInstance() {
        ActivityLifecycleMonitor monitor = instance;
        if (monitor == null) {
            throw new IllegalStateException("No lifecycle monitor registered!");
        }
        return monitor;
    }

    public static void registerInstance(ActivityLifecycleMonitor monitor) {
        instance = monitor;
    }
}
