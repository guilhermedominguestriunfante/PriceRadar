package androidx.test.internal.runner.intent;

import android.content.Intent;
import androidx.test.runner.intent.IntentCallback;
import androidx.test.runner.intent.IntentMonitor;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class IntentMonitorImpl implements IntentMonitor {
    private final List<IntentCallback> callbacks = new CopyOnWriteArrayList<>();

    public IntentMonitorImpl() {}

    @Override
    public void addIntentCallback(IntentCallback callback) {
        callbacks.add(callback);
    }

    @Override
    public void removeIntentCallback(IntentCallback callback) {
        callbacks.remove(callback);
    }

    public void signalIntent(Intent intent) {
        for (IntentCallback callback : callbacks) {
            callback.onIntentSent(new Intent(intent));
        }
    }
}
