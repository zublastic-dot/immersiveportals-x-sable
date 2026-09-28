package qouteall.imm_ptl.core.teleportation;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Keeps portal entity queries and sound playback on the client thread. */
public final class ClientSoundDispatch {
    private ClientSoundDispatch() {}

    /** Return true when the caller must cancel its off-thread playback. */
    public static boolean defer(boolean onClientThread, Executor clientExecutor,
                                BooleanSupplier worldStillLoaded, Runnable playback) {
        if (onClientThread) return false;
        clientExecutor.execute(() -> {
            // Evaluate on the client thread, after a possible disconnect/world unload.
            if (worldStillLoaded.getAsBoolean()) playback.run();
        });
        return true;
    }
}
