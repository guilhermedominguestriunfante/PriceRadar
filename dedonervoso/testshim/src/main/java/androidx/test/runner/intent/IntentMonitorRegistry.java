package androidx.test.runner.intent;

public final class IntentMonitorRegistry {
    private static volatile IntentMonitor instance;

    private IntentMonitorRegistry() {}

    public static IntentMonitor getInstance() {
        IntentMonitor monitor = instance;
        if (monitor == null) {
            throw new IllegalStateException("No intent monitor registered!");
        }
        return monitor;
    }

    public static void registerInstance(IntentMonitor monitor) {
        instance = monitor;
    }
}
