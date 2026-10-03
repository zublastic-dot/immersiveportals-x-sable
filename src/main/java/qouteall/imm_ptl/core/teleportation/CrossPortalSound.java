package qouteall.imm_ptl.core.teleportation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Compatibility factory; all playback now goes through native channel transport. */
public class CrossPortalSound {
    public static final float VOLUME_RADIUS_MULT = 16f;
    public static final float MIN_SOUND_RADIUS = 16f;

    public static boolean isPlayerWorld(ClientLevel world) {
        var player = Minecraft.getInstance().player;
        return player != null && player.level() == world;
    }

    /**
     * Retain original coordinates and gain. PortalSoundManager resolves the current
     * route when this same instance is played and subsequently while it is active.
     */
    @Nullable
    public static SimpleSoundInstance createCrossPortalSound(ClientLevel soundWorld,
        SoundEvent soundEvent, SoundSource soundSource, Vec3 soundPos,
        float soundVol, float soundPitch, long seed) {
        if (Minecraft.getInstance().player == null) return null;
        SimpleSoundInstance sound = new SimpleSoundInstance(soundEvent, soundSource, soundVol, soundPitch,
            RandomSource.create(seed), soundPos.x, soundPos.y, soundPos.z);
        try (var ignored = PortalSoundManager.producer(soundWorld)) {
            return PortalSoundManager.bind(sound) ? sound : null;
        }
    }
}
