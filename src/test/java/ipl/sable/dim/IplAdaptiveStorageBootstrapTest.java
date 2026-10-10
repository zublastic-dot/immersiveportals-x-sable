package ipl.sable.dim;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.tags.TagKey;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real registry serialization plus exact Minecraft/Mixin boundary contracts, not a live mixin launch. */
class IplAdaptiveStorageBootstrapTest {
    @Test void modifiedHostingTypeIsSentEvenWhenClientAlreadyKnowsTheDatapack() throws Exception {
        qouteall.imm_ptl.core.portal.PortalBlockTestBootstrap.initialize();
        KnownPack pack = new KnownPack("test", "shared", "1");
        RegistrationInfo known = new RegistrationInfo(Optional.of(pack), Lifecycle.stable());
        ResourceKey<DimensionType> hosting = key("ipl_sable", "sublevels");
        ResourceKey<DimensionType> ordinary = key("test", "ordinary");
        MappedRegistry<DimensionType> registry = new MappedRegistry<>(Registries.DIMENSION_TYPE, Lifecycle.stable());
        DimensionType hostingType = type(-96, 608);
        DimensionType ordinaryType = type(-64, 384);
        registry.register(hosting, hostingType, known);
        registry.register(ordinary, ordinaryType, known);
        RegistryAccess access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry)).freeze();
        var before = packets(access, pack);
        assertTrue(before.stream().allMatch(entry -> entry.data().isEmpty()), "Fixture reproduces known-pack payload omission");

        var field = MappedRegistry.class.getDeclaredField("registrationInfos");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<ResourceKey<?>, RegistrationInfo> infos = (Map<ResourceKey<?>, RegistrationInfo>) field.get(registry);
        IplAdaptiveStorageBootstrap.clearKnownPack(infos, hosting);
        var after = packets(access, pack);
        var hostingPacket = after.stream().filter(entry -> entry.id().equals(hosting.location())).findFirst().orElseThrow();
        CompoundTag serialized = (CompoundTag) hostingPacket.data().orElseThrow();
        assertEquals(-96, serialized.getInt("min_y"));
        assertEquals(608, serialized.getInt("height"));
        assertEquals(608, serialized.getInt("logical_height"));
        assertTrue(after.stream().filter(entry -> entry.id().equals(ordinary.location())).findFirst().orElseThrow().data().isEmpty());
        assertSame(known, infos.get(ordinary), "Ordinary registration metadata stays untouched");
        assertSame(ordinaryType, registry.get(ordinary));
        assertEquals(-64, ordinaryType.minY());
        assertEquals(384, ordinaryType.height());
        assertSame(known.lifecycle(), infos.get(hosting).lifecycle());
    }

    @Test void startupHookIsRequiredBeforeTheFirstServerLevelConstructor() throws Exception {
        ClassNode server = read("net/minecraft/server/MinecraftServer");
        MethodNode target = server.methods.stream().filter(method -> method.name.equals("createLevels")
            && method.desc.equals("(Lnet/minecraft/server/level/progress/ChunkProgressListener;)V")).findFirst().orElseThrow();
        assertTrue(target.instructions.iterator().hasNext());
        boolean constructsLevel = false;
        for (var instruction : target.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/server/level/ServerLevel")
                && call.name.equals("<init>")) constructsLevel = true;
        }
        assertTrue(constructsLevel, "This is the actual storage/light allocation boundary");
        ClassNode mixin = read("ipl/sable/mixin/IplAdaptiveHostingStorageMixin");
        MethodNode hook = mixin.methods.stream().filter(method -> method.name.equals("iplsable$selectStorageProfile")).findFirst().orElseThrow();
        AnnotationNode inject = hook.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        assertEquals(List.of("createLevels"), annotationValue(inject, "method"));
        assertEquals(1, annotationValue(inject, "require"));
        @SuppressWarnings("unchecked") List<AnnotationNode> sites = (List<AnnotationNode>) annotationValue(inject, "at");
        assertEquals("HEAD", annotationValue(sites.getFirst(), "value"));
    }

    @Test void storageSelectionReadsDimensionTypesAndAccessorTargetsMatchRuntimeFields() throws Exception {
        ClassNode bootstrap = read("ipl/sable/dim/IplAdaptiveStorageBootstrap");
        boolean readsMin = false, readsHeight = false;
        for (MethodNode method : bootstrap.methods) for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            assertFalse(Set.of("getMinBuildHeight", "getMaxBuildHeight", "getHeight").contains(call.name),
                "Selection must not read contextual Level height methods");
            if (call.owner.equals("net/minecraft/world/level/dimension/DimensionType")) {
                readsMin |= call.name.equals("minY");
                readsHeight |= call.name.equals("height");
            }
        }
        assertTrue(readsMin && readsHeight);
        ClassNode type = read("net/minecraft/world/level/dimension/DimensionType");
        ClassNode accessor = read("ipl/sable/mixin/IplDimensionTypeHeightAccessor");
        for (String name : List.of("minY", "height", "logicalHeight")) {
            assertTrue(type.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals("I")));
            assertTrue(accessor.methods.stream().anyMatch(method -> method.visibleAnnotations != null
                && method.visibleAnnotations.stream().anyMatch(a -> a.desc.endsWith("/Accessor;") && name.equals(annotationValue(a, "value")))));
        }
        assertTrue(read("net/minecraft/core/MappedRegistry").fields.stream()
            .anyMatch(field -> field.name.equals("registrationInfos") && field.desc.equals("Ljava/util/Map;")));
    }

    private static List<RegistrySynchronization.PackedRegistryEntry> packets(RegistryAccess access, KnownPack pack) {
        List<RegistrySynchronization.PackedRegistryEntry> entries = new ArrayList<>();
        RegistrySynchronization.packRegistries(NbtOps.INSTANCE, access, Set.of(pack), (key, value) -> {
            if (key.equals(Registries.DIMENSION_TYPE)) entries.addAll(value);
        });
        return entries;
    }

    private static DimensionType type(int minY, int height) {
        return new DimensionType(OptionalLong.empty(), true, false, false, true, 1.0, true, false,
            minY, height, height, TagKey.create(Registries.BLOCK, ResourceLocation.withDefaultNamespace("infiniburn_overworld")),
            ResourceLocation.withDefaultNamespace("overworld"), 0.0f,
            new DimensionType.MonsterSettings(false, true, ConstantInt.of(0), 0));
    }

    private static ResourceKey<DimensionType> key(String namespace, String path) {
        return ResourceKey.create(Registries.DIMENSION_TYPE, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        }
        return null;
    }

    private static ClassNode read(String name) throws Exception {
        try (var stream = IplAdaptiveStorageBootstrapTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream, name);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }
}
