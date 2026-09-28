package androidx.test.internal.platform.os;

import android.view.View;

public interface ControlledLooper {
    void drainMainThreadUntilIdle();

    void simulateWindowFocus(View decorView);
}
