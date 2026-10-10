package ipl.sable.dim;

import ipl.sable.mixin.IplDeferredAirSectionsMixin;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class IplDeferredAirSectionsTest {
    public static class OwnerChunk extends IplDeferredAirSectionsMixin {}
    public static class Imposter extends OwnerChunk {
        private final OwnerChunk wrapped;
        public Imposter(OwnerChunk wrapped) { this.wrapped = wrapped; }
        public OwnerChunk getWrapped() { return wrapped; }
    }

    @Test void chunkOwnsAnIndependentArchiveAndSavingDoesNotAliasIt() {
        OwnerChunk chunk = new OwnerChunk();
        assertNull(chunk.iplsable$getDeferredAirSections(), "Ordinary chunks allocate no archive");
        ListTag input = archive();
        chunk.iplsable$setDeferredAirSections(input);
        input.getCompound(0).putString("example:metadata", "caller changed");
        assertEquals("original", chunk.iplsable$getDeferredAirSections().getCompound(0).getString("example:metadata"));
        CompoundTag save = new CompoundTag();
        IplHostingChunkStorageMigration.writeDeferredAirSections(save, chunk.iplsable$getDeferredAirSections());
        save.getList(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, Tag.TAG_COMPOUND)
            .getCompound(0).putString("example:metadata", "save changed");
        assertEquals("original", chunk.iplsable$getDeferredAirSections().getCompound(0).getString("example:metadata"));
        chunk.iplsable$setDeferredAirSections(new ListTag());
        assertNull(chunk.iplsable$getDeferredAirSections());
    }

    @Test void compiledOwnerLookupUnwrapsFullChunksAndKeepsProtoOwner() throws Exception {
        Class<?> fixture = fixture("ipl/sable/dim/IplDeferredAirSections", "owner", Map.of());
        var method = fixture.getMethod("owner", OwnerChunk.class);
        OwnerChunk real = new OwnerChunk();
        Imposter wrapper = new Imposter(real);
        assertSame(real, method.invoke(null, real));
        assertSame(real, method.invoke(null, wrapper));
    }

    @Test void compiledPromotionHookCopiesArchiveAndTargetsTheActualConstructor() throws Exception {
        String mixin = "ipl/sable/mixin/IplDeferredAirSectionsPromotionMixin";
        ClassNode node = read(mixin);
        MethodNode hook = node.methods.stream().filter(m -> m.name.equals("iplsable$promoteDeferredAirSections")).findFirst().orElseThrow();
        AnnotationNode inject = hook.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        String target = (String) ((List<?>) value(inject, "method")).getFirst();
        assertTrue(read("net/minecraft/world/level/chunk/LevelChunk").methods.stream()
            .anyMatch(m -> m.name.equals("<init>") && target.equals(m.name + m.desc)));
        assertEquals(1, value(inject, "require"));
        AnnotationNode site = (AnnotationNode) ((List<?>) value(inject, "at")).getFirst();
        assertEquals("RETURN", value(site, "value"));

        Class<?> fixture = fixture(mixin, hook.name, Map.of(
            "net/minecraft/server/level/ServerLevel", "java/lang/Object",
            "net/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor", "java/lang/Object"));
        OwnerChunk source = new OwnerChunk();
        source.iplsable$setDeferredAirSections(archive());
        OwnerChunk promoted = (OwnerChunk) fixture.getConstructor().newInstance();
        fixture.getMethod(hook.name, Object.class, OwnerChunk.class, Object.class, CallbackInfo.class)
            .invoke(promoted, null, source, null, new CallbackInfo("promotion", false));
        assertEquals(source.iplsable$getDeferredAirSections(), promoted.iplsable$getDeferredAirSections());
        assertSame(source.iplsable$getDeferredAirArchive(), promoted.iplsable$getDeferredAirArchive(),
            "Promotion shares immutable compressed bytes and does not allocate another NBT graph");
        ListTag changed = source.iplsable$getDeferredAirSections();
        changed.getCompound(0).putString("example:metadata", "changed source");
        source.iplsable$setDeferredAirSections(changed);
        assertEquals("original", promoted.iplsable$getDeferredAirSections().getCompound(0).getString("example:metadata"));
    }

    @Test void representative216SectionPaddingRemainsCompactAndLossless() throws Exception {
        ListTag sections = new ListTag();
        for (int y = -127; y <= 126; y++) {
            if (y >= -6 && y < 32) continue;
            CompoundTag section = archive().getCompound(0).copy();
            section.putInt("Y", y);
            CompoundTag states = new CompoundTag();
            CompoundTag air = new CompoundTag();
            air.putString("Name", "minecraft:air");
            ListTag palette = new ListTag();
            palette.add(air);
            states.put("palette", palette);
            section.put("block_states", states);
            sections.add(section);
        }
        assertEquals(216, sections.size());
        CompoundTag root = new CompoundTag();
        root.put(IplHostingChunkStorageMigration.DEFERRED_AIR_SECTIONS_TAG, sections);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        NbtIo.write(root, new DataOutputStream(raw));
        IplDeferredAirSectionArchive archive = IplDeferredAirSectionArchive.encode(sections);
        assertNotNull(archive);
        assertEquals(sections, archive.decode());
        assertTrue(archive.encodedByteCount() < raw.size() / 8,
            "Repeated empty-section metadata must not remain as hundreds of retained CompoundTags");
        ListTag decoded = archive.decode();
        decoded.clear();
        sections.getCompound(0).putString("example:metadata", "source mutation");
        assertEquals(216, archive.decode().size());
        assertEquals("original", archive.decode().getCompound(0).getString("example:metadata"));
    }

    @Test void encodingAndDecompressionEnforceIndependentByteLimits() {
        ListTag sections = archive();
        sections.getCompound(0).putString("example:compressible", "x".repeat(32_000));
        assertThrows(IllegalStateException.class, () -> IplDeferredAirSectionArchive.encode(sections, 8, 1 << 20));
        assertThrows(IllegalStateException.class, () -> IplDeferredAirSectionArchive.encode(sections, 1 << 20, 128));
        IplDeferredAirSectionArchive archive = IplDeferredAirSectionArchive.encode(sections);
        assertNotNull(archive);
        assertTrue(archive.encodedByteCount() < 1024);
        assertThrows(IllegalStateException.class, () -> archive.decode(256),
            "A small compressed payload cannot bypass the decoded-byte/NBT allocation bounds");
        assertEquals(sections, archive.decode());
    }

    private static ListTag archive() {
        CompoundTag section = new CompoundTag();
        section.putInt("Y", 100);
        section.putString("example:metadata", "original");
        CompoundTag biomes = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(StringTag.valueOf("minecraft:desert"));
        biomes.put("palette", palette);
        section.put("biomes", biomes);
        ListTag archive = new ListTag();
        archive.add(section);
        return archive;
    }

    /** Execute the production method bodies with small owners, without starting a world or Mixin runtime. */
    private static Class<?> fixture(String sourceName, String methodName, Map<String, String> additional) throws Exception {
        ClassNode source = read(sourceName);
        ClassNode target = new ClassNode();
        target.version = Opcodes.V21;
        target.access = Opcodes.ACC_PUBLIC;
        target.name = "ipl/sable/dim/DeferredArchive" + (methodName.equals("owner") ? "Owner" : "Promotion") + "Fixture";
        target.superName = Type.getInternalName(OwnerChunk.class);
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, target.superName, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        target.methods.add(constructor);
        MethodNode body = source.methods.stream().filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
        body.access = Opcodes.ACC_PUBLIC | (body.access & Opcodes.ACC_STATIC);
        target.methods.add(body);
        Map<String, String> names = new HashMap<>(additional);
        if (!sourceName.equals("ipl/sable/dim/IplDeferredAirSections")) names.put(sourceName, target.name);
        names.put("net/minecraft/world/level/chunk/ChunkAccess", Type.getInternalName(OwnerChunk.class));
        names.put("net/minecraft/world/level/chunk/ProtoChunk", Type.getInternalName(OwnerChunk.class));
        names.put("net/minecraft/world/level/chunk/LevelChunk", Type.getInternalName(OwnerChunk.class));
        names.put("net/minecraft/world/level/chunk/ImposterProtoChunk", Type.getInternalName(Imposter.class));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        target.accept(new ClassRemapper(writer, new SimpleRemapper(names)));
        class Loader extends ClassLoader { Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); } }
        return new Loader().define(writer.toByteArray());
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }

    private static ClassNode read(String path) throws Exception {
        try (var input = IplDeferredAirSectionsTest.class.getResourceAsStream("/" + path + ".class")) {
            assertNotNull(input);
            ClassNode node = new ClassNode(Opcodes.ASM9);
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
