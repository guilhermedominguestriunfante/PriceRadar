package androidx.test.internal.runner.lifecycle;

import android.app.Activity;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.Stage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tracks the lifecycle stage of every activity signalled by the instrumentation. */
public final class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
    private final List<ActivityLifecycleCallback> callbacks = new CopyOnWriteArrayList<>();
    private final Map<Activity, Stage> stages = new WeakHashMap<>();

    public ActivityLifecycleMonitorImpl() {}

    public ActivityLifecycleMonitorImpl(boolean declawThreadCheck) {}

    @Override
    public void addLifecycleCallback(ActivityLifecycleCallback callback) {
        callbacks.add(callback);
    }

    @Override
    public void removeLifecycleCallback(ActivityLifecycleCallback callback) {
        callbacks.remove(callback);
    }

    @Override
    public synchronized Stage getLifecycleStageOf(Activity activity) {
        Stage stage = stages.get(activity);
        if (stage == null) {
            throw new IllegalArgumentException("Unknown activity: " + activity);
        }
        return stage;
    }

    @Override
    public synchronized Collection<Activity> getActivitiesInStage(Stage stage) {
        List<Activity> result = new ArrayList<>();
        for (Map.Entry<Activity, Stage> entry : stages.entrySet()) {
            if (entry.getValue() == stage) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public void signalLifecycleChange(Stage stage, Activity activity) {
        synchronized (this) {
            stages.put(activity, stage);
        }
        for (ActivityLifecycleCallback callback : callbacks) {
            callback.onActivityLifecycleChanged(activity, stage);
        }
    }
}
