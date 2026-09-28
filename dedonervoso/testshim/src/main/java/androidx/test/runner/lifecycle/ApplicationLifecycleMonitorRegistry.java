package androidx.test.runner.lifecycle;

public final class ApplicationLifecycleMonitorRegistry {
    private static volatile ApplicationLifecycleMonitor instance;

    private ApplicationLifecycleMonitorRegistry() {}

    public static ApplicationLifecycleMonitor getInstance() {
        ApplicationLifecycleMonitor monitor = instance;
        if (monitor == null) {
            throw new IllegalStateException("No application lifecycle monitor registered!");
        }
        return monitor;
    }

    public static void registerInstance(ApplicationLifecycleMonitor monitor) {
        instance = monitor;
    }
}
