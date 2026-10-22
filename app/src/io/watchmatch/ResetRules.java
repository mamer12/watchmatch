package io.watchmatch;

import java.util.ArrayList;
import java.util.List;

/**
 * "Reset {catalog} data": the clear steps run one after another, each injected and allowed to fail without stopping the
 * rest (no Android here, WatchmeRulesCheck runs it with fakes).
 */
final class ResetRules {
    private ResetRules() {}

    interface Step { void run() throws Exception; }

    static final class Result {
        final List<String> ok = new ArrayList<>(), failed = new ArrayList<>();
    }

    /** Runs every step in order; a step that throws is listed in `failed` (with its message) and the rest still run. */
    static Result run(String[] names, Step[] steps) {
        Result r = new Result();
        for (int i = 0; i < steps.length; i++) {
            try {
                steps[i].run();
                r.ok.add(names[i]);
            } catch (Exception | LinkageError e) {
                r.failed.add(names[i] + ": " + e);
            }
        }
        return r;
    }
}
