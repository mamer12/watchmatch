package io.watchmatch;

/** A damped spring for one number. Callers set {@link #target} and call {@link #step} once per frame. */
final class Spring {
    private static final float STEP = 0.004f;
    private static final float POS_EPS = 0.0008f;
    private static final float VEL_EPS = 0.004f;

    /** Reduce motion (set by A11y): every spring jumps straight to its target. */
    static volatile boolean instant;

    float value, velocity, target;
    private final float stiffness, friction;

    /** @param response seconds per undamped cycle; @param damping 1 = no overshoot, below 1 = bounce */
    Spring(float response, float damping, float initial) {
        float omega = (float) (2 * Math.PI / response);
        stiffness = omega * omega;
        friction = 2 * damping * omega;
        value = target = initial;
    }

    void snap(float v) {
        value = target = v;
        velocity = 0;
    }

    /** True while still moving. The call after landing inside the tolerance is the one that snaps and returns false. */
    boolean step(float dt) {
        if (instant || settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        // Small fixed substeps keep the explicit integrator stable on slow frames.
        int n = Math.max(1, (int) Math.ceil(dt / STEP));
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            velocity += (-stiffness * (value - target) - friction * velocity) * h;
            value += velocity * h;
        }
        return true;
    }

    boolean settled() {
        return Math.abs(value - target) < POS_EPS && Math.abs(velocity) < VEL_EPS;
    }
}
