package androidx.test.runner.intent;

public final class IntentStubberRegistry {
    private static volatile IntentStubber instance;

    private IntentStubberRegistry() {}

    public static void load(IntentStubber stubber) {
        instance = stubber;
    }

    public static boolean isLoaded() {
        return instance != null;
    }

    public static IntentStubber getInstance() {
        IntentStubber stubber = instance;
        if (stubber == null) {
            throw new IllegalStateException("No intent stubber loaded!");
        }
        return stubber;
    }

    public static void reset() {
        instance = null;
    }
}
