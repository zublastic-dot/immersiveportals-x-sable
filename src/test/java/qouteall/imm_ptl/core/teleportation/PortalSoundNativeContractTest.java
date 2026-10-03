package qouteall.imm_ptl.core.teleportation;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Inspect the pinned native classes: these seams must not silently become dead injections. */
class PortalSoundNativeContractTest {
    @Test void nativePlayAndTickEachHaveOnePositionConstructorAndPlayHasOneSetupDispatch() throws IOException {
        ClassNode engine = node("net/minecraft/client/sounds/SoundEngine");
        MethodNode play = method(engine, "play", "(Lnet/minecraft/client/resources/sounds/SoundInstance;)V");
        MethodNode tick = method(engine, "tickNonPaused", "()V");
        assertEquals(1, calls(play, "net/minecraft/world/phys/Vec3", "<init>", "(DDD)V"));
        assertEquals(1, calls(tick, "net/minecraft/world/phys/Vec3", "<init>", "(DDD)V"));
        assertEquals(1, calls(play, "net/minecraft/client/sounds/ChannelAccess$ChannelHandle", "execute", "(Ljava/util/function/Consumer;)V"));
        assertEquals(1, calls(play, "net/neoforged/neoforge/client/ClientHooks", "playSound", "(Lnet/minecraft/client/sounds/SoundEngine;Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/resources/sounds/SoundInstance;"));
        method(engine, "stopAll", "()V");
        method(engine, "calculateVolume", "(Lnet/minecraft/client/resources/sounds/SoundInstance;)F");
        for (String field : List.of("instanceToChannel", "queuedSounds", "queuedTickableSounds", "listener")) {
            assertTrue(engine.fields.stream().anyMatch(f -> f.name.equals(field)), field);
        }
    }

    @Test void jukeboxUsesOneNativeInstanceForPlayAndStopWithoutClientLevelEvent() throws IOException {
        ClassNode renderer = node("net/minecraft/client/renderer/LevelRenderer");
        MethodNode play = method(renderer, "playJukeboxSong", "(Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)V");
        MethodNode stop = method(renderer, "stopJukeboxSong", "(Lnet/minecraft/core/BlockPos;)V");
        assertEquals(1, calls(play, "net/minecraft/client/sounds/SoundManager", "play", "(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"));
        assertEquals(1, calls(stop, "net/minecraft/client/sounds/SoundManager", "stop", "(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"));
        assertEquals(1, calls(play, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
        assertEquals(1, calls(stop, "java/util/Map", "remove", "(Ljava/lang/Object;)Ljava/lang/Object;"));
    }

    @Test void channelReuseHasExactSourceIdentityAndStopAndDestroySeams() throws IOException {
        ClassNode channel = node("com/mojang/blaze3d/audio/Channel");
        FieldNode source = channel.fields.stream().filter(f -> f.name.equals("source")).findFirst().orElseThrow();
        assertEquals("I", source.desc);
        assertTrue((source.access & Opcodes.ACC_FINAL) != 0);
        method(channel, "stop", "()V");
        method(channel, "destroy", "()V");
        method(channel, "setSelfPosition", "(Lnet/minecraft/world/phys/Vec3;)V");
        method(channel, "setVolume", "(F)V");
    }

    @Test void entityAndDelayedNativeProducersAreCovered() throws IOException {
        ClassNode level = node("net/minecraft/client/multiplayer/ClientLevel");
        MethodNode positional = method(level, "playSound", "(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZJ)V");
        assertEquals(1, calls(positional, "net/minecraft/client/sounds/SoundManager", "playDelayed", "(Lnet/minecraft/client/resources/sounds/SoundInstance;I)V"));
        assertEquals(1, calls(positional, "net/minecraft/world/phys/Vec3", "distanceToSqr", "(DDD)D"));
        method(node("net/minecraft/client/sounds/SoundManager"), "queueTickingSound",
            "(Lnet/minecraft/client/resources/sounds/TickableSoundInstance;)V");
        MethodNode entity = method(level, "playLocalSound", "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V");
        assertEquals(1, calls(entity, "net/minecraft/client/sounds/SoundManager", "play", "(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"));
        FieldNode emitter = node("net/minecraft/client/resources/sounds/EntityBoundSoundInstance").fields.stream()
            .filter(f -> f.name.equals("entity")).findFirst().orElseThrow();
        assertEquals("Lnet/minecraft/world/entity/Entity;", emitter.desc);
        assertTrue((emitter.access & Opcodes.ACC_FINAL) != 0);
    }

    private static ClassNode node(String name) throws IOException {
        try (var input = PortalSoundNativeContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
            .orElseThrow(() -> new AssertionError(node.name + '.' + name + descriptor));
    }
    private static long calls(MethodNode m, String owner, String name, String descriptor) {
        long count = 0;
        for (var insn : m.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
            && call.name.equals(name) && call.desc.equals(descriptor)) count++;
        return count;
    }
}
