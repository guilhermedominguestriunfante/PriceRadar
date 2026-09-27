package androidx.test.espresso;

import android.os.Looper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class IdlingRegistry {
    private static final IdlingRegistry INSTANCE = new IdlingRegistry();
    private final List<IdlingResource> resources = new CopyOnWriteArrayList<>();
    private final List<Looper> loopers = new CopyOnWriteArrayList<>();

    private IdlingRegistry() {}

    public static IdlingRegistry getInstance() {
        return INSTANCE;
    }

    public boolean register(IdlingResource... idlingResources) {
        boolean changed = false;
        for (IdlingResource resource : idlingResources) {
            if (!resources.contains(resource)) {
                changed |= resources.add(resource);
            }
        }
        return changed;
    }

    public boolean unregister(IdlingResource... idlingResources) {
        boolean changed = false;
        for (IdlingResource resource : idlingResources) {
            changed |= resources.remove(resource);
        }
        return changed;
    }

    public void registerLooperAsIdlingResource(Looper looper) {
        if (!loopers.contains(looper)) {
            loopers.add(looper);
        }
    }

    public boolean unregisterLooperAsIdlingResource(Looper looper) {
        return loopers.remove(looper);
    }

    public Collection<IdlingResource> getResources() {
        return new ArrayList<>(resources);
    }

    public Collection<Looper> getLoopers() {
        return new ArrayList<>(loopers);
    }
}
