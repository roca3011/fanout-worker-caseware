package com.caseware.templateupdates.fixtures;

import java.util.concurrent.Executor;

public final class DirectExecutor implements Executor {

    @Override
    public void execute(Runnable command) {
        command.run();
    }
}
