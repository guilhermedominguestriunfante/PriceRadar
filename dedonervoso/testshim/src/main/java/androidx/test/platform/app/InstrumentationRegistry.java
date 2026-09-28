package androidx.test.platform.app;

import android.app.Instrumentation;
import android.os.Bundle;

public final class InstrumentationRegistry {
    private static volatile Instrumentation instrumentation;
    private static volatile Bundle arguments = new Bundle();

    private InstrumentationRegistry() {}

    public static Instrumentation getInstrumentation() {
        Instrumentation value = instrumentation;
        if (value == null) {
            throw new IllegalStateException("No instrumentation registered!");
        }
        return value;
    }

    public static Bundle getArguments() {
        return new Bundle(arguments);
    }

    public static void registerInstance(Instrumentation instrumentation, Bundle arguments) {
        InstrumentationRegistry.instrumentation = instrumentation;
        InstrumentationRegistry.arguments = arguments == null ? new Bundle() : new Bundle(arguments);
    }
}
