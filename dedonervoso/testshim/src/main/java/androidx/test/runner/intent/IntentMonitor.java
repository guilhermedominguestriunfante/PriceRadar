package androidx.test.runner.intent;

public interface IntentMonitor {
    void addIntentCallback(IntentCallback callback);

    void removeIntentCallback(IntentCallback callback);
}
