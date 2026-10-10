package ipl.sable.transit;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the compiled anchor lookup methods with indexed worlds, without booting Minecraft. */
public class IplShipLookupTest {
    private static final String SERVER = "ipl/sable/transit/IplShipPortalAnchor";
    private static final String CLIENT = "ipl/sable/client/IplClientShipPortalAnchor";
    private static final String CONTAINER = "dev/ryanhcode/sable/api/sublevel/SubLevelContainer";
    private static final UUID ID = new UUID(7, 19);

    public static class Ship {
        final UUID id;
        boolean removed;
        Ship(UUID id) { this.id = id; }
        public UUID getUniqueId() { return id; }
        public boolean isRemoved() { return removed; }
    }
    public static final class ClientShip extends Ship { ClientShip(UUID id) { super(id); } }
    public static final class ServerShip extends Ship { ServerShip(UUID id) { super(id); } }
    public static final class World {
        final Container container;
        World(Container container) { this.container = container; }
    }
    public static final class Container {
        final Map<UUID, Ship> ships = new LinkedHashMap<>();
        int probes;
        public static Container getContainer(World world) { return world.container; }
        public Ship getSubLevel(UUID id) { probes++; return ships.get(id); }
        public List<Ship> getAllSubLevels() { throw new AssertionError("Anchor lookup enumerated ships"); }
        Container with(Ship ship) { ships.put(ship.id, ship); return this; }
    }
    public static final class Worlds {
        static List<World> current = List.of();
        public Iterable<World> getAllLevels() { return current; }
        public static Collection<World> getClientWorlds() { return current; }
    }
    private record Lookup(boolean client, Method method) {
        Ship find(UUID id) throws Exception {
            return (Ship) (client ? method.invoke(null, id) : method.invoke(null, new Worlds(), id));
        }
        Ship ship(UUID id) { return client ? new ClientShip(id) : new ServerShip(id); }
        Ship wrongType(UUID id) { return client ? new ServerShip(id) : new ClientShip(id); }
    }

    @Test void skipsMissingContainersAndWrongTypesButPreservesFirstWorldOrder() throws Exception {
        for (Lookup lookup : lookups()) {
            Container empty = new Container();
            Container wrong = new Container().with(lookup.wrongType(ID));
            Ship first = lookup.ship(ID);
            Ship later = lookup.ship(ID);
            Container firstWorld = new Container().with(first);
            Container laterWorld = new Container().with(later);
            Worlds.current = List.of(new World(null), new World(empty), new World(wrong),
                new World(firstWorld), new World(laterWorld));
            assertSame(first, lookup.find(ID));
            assertEquals(1, empty.probes);
            assertEquals(1, wrong.probes);
            assertEquals(1, firstWorld.probes);
            assertEquals(0, laterWorld.probes, "A later duplicate must not replace the first match");
            assertNull(lookup.find(new UUID(0, 99)));
            Worlds.current = List.of();
            assertNull(lookup.find(ID));
        }
    }

    @Test void removalAndRehomeAreObservedWithoutAnyAnchorLookupCache() throws Exception {
        for (Lookup lookup : lookups()) {
            Ship original = lookup.ship(ID);
            Container origin = new Container().with(original);
            Container destination = new Container();
            Worlds.current = List.of(new World(origin), new World(destination));
            assertSame(original, lookup.find(ID));
            original.removed = true;
            // Preserve the previous contract: callers handle the removed flag, before container cleanup.
            assertSame(original, lookup.find(ID));
            assertTrue(lookup.find(ID).isRemoved());
            origin.ships.remove(ID);
            assertNull(lookup.find(ID));
            Ship replacement = lookup.ship(ID);
            destination.with(replacement);
            assertSame(replacement, lookup.find(ID));
        }
        assertRemovedGuard(SERVER, "tickAll");
        assertRemovedGuard(SERVER, "lambda$samplePoses$");
        assertRemovedGuard(CLIENT, "resolveImpostorPose");
        assertRemovedGuard(CLIENT, "lambda$driveAll$");
    }

    @Test void lookupProbeCountDependsOnWorldCountInsteadOfShipCount() throws Exception {
        for (Lookup lookup : lookups()) {
            for (int size : new int[]{1, 64, 4096}) {
                List<Container> containers = populate(lookup, size);
                Ship expected = containers.getLast().ships.get(ID);
                assertSame(expected, lookup.find(ID));
                assertEquals(3, containers.stream().mapToInt(c -> c.probes).sum());
                assertNull(lookup.find(new UUID(88, 99)));
                assertEquals(6, containers.stream().mapToInt(c -> c.probes).sum());
            }
        }
    }

    @Test void sableIndexUsesItsMaintainedUuidMap() throws Exception {
        ClassNode sable = read(CONTAINER);
        MethodNode indexed = sable.methods.stream().filter(m -> m.name.equals("getSubLevel")
            && m.desc.startsWith("(Ljava/util/UUID;)")).findFirst().orElseThrow();
        assertTrue(calls(indexed, "java/util/Map", "get"));
        assertFalse(calls(indexed, "java/util/List", "iterator"));
        MethodNode allocation = named(sable, "allocateSubLevel");
        MethodNode removal = sable.methods.stream().filter(m -> m.name.equals("removeSubLevel")
            && m.desc.startsWith("(II")).findFirst().orElseThrow();
        assertTrue(calls(allocation, "java/util/Map", "put"));
        assertTrue(calls(removal, "java/util/Map", "remove"));
    }

    @Test void inactiveRouteTracingSkipsArgumentArrayAndObservation() throws Exception {
        MethodNode blockRead = named(read("ipl/sable/mixin/IplHostedWorldFrameRouterMixin"), "getBlockState");
        MethodInsnNode traceCheck = call(blockRead, "isTracing");
        var next = traceCheck.getNext();
        while (next.getOpcode() < 0) next = next.getNext();
        assertInstanceOf(JumpInsnNode.class, next);
        JumpInsnNode branch = (JumpInsnNode) next;
        assertEquals(Opcodes.IFEQ, branch.getOpcode());
        int checkIndex = blockRead.instructions.indexOf(branch);
        int afterTrace = blockRead.instructions.indexOf(branch.label);
        int observeIndex = blockRead.instructions.indexOf(call(blockRead, "observeRoute"));
        assertTrue(checkIndex < observeIndex && observeIndex < afterTrace);
        long guardedArrays = 0;
        for (var instruction : blockRead.instructions) {
            if (instruction.getOpcode() == Opcodes.ANEWARRAY) {
                int index = blockRead.instructions.indexOf(instruction);
                assertTrue(checkIndex < index && index < afterTrace);
                guardedArrays++;
            }
        }
        assertEquals(1, guardedArrays);
    }

    /** Informational fixture benchmark: no timing assertion or claim about Minecraft FPS/MSPT. */
    @Test void reportIndexedLookupVersusLinearReference() throws Exception {
        final int repetitions = 2000;
        for (Lookup lookup : lookups()) {
            for (int size : new int[]{1, 64, 4096}) {
                List<Container> containers = populate(lookup, size);
                Ship expected = containers.getLast().ships.get(ID);
                for (int i = 0; i < 500; i++) {
                    assertSame(expected, lookup.find(ID));
                    assertSame(expected, linearReference(containers));
                }
                containers.forEach(c -> c.probes = 0);
                long start = System.nanoTime();
                Ship last = null;
                for (int i = 0; i < repetitions; i++) last = lookup.find(ID);
                long indexedNs = System.nanoTime() - start;
                assertSame(expected, last);
                start = System.nanoTime();
                for (int i = 0; i < repetitions; i++) last = linearReference(containers);
                long linearNs = System.nanoTime() - start;
                assertSame(expected, last);
                int probes = containers.stream().mapToInt(c -> c.probes).sum();
                assertEquals(repetitions * 3, probes);
                System.out.printf("SHIP_LOOKUP side=%s worlds=3 shipsPerWorld=%d iterations=%d indexedProbes=%d "
                        + "linearReferenceVisits=%d indexedNs=%d linearReferenceNs=%d "
                        + "fixtureOnly=true indexedIncludesReflection=true%n",
                    lookup.client ? "client" : "server", size, repetitions, probes,
                    (long) repetitions * 3 * size, indexedNs, linearNs);
            }
        }
    }

    private static Ship linearReference(List<Container> containers) {
        for (Container container : containers) for (Ship ship : container.ships.values()) {
            if (ship.getUniqueId().equals(ID)) return ship;
        }
        return null;
    }

    private static List<Container> populate(Lookup lookup, int size) {
        List<Container> containers = new ArrayList<>();
        for (int world = 0; world < 3; world++) {
            Container container = new Container();
            for (int i = 0; i < size; i++) container.with(lookup.ship(
                world == 2 && i == size - 1 ? ID : new UUID(world, i)));
            containers.add(container);
        }
        Worlds.current = containers.stream().map(World::new).toList();
        return containers;
    }

    private static List<Lookup> lookups() throws Exception {
        return List.of(lookup(SERVER, false), lookup(CLIENT, true));
    }

    private static Lookup lookup(String owner, boolean client) throws Exception {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "ipl/sable/transit/ShipLookupFixture";
        node.superName = "java/lang/Object";
        MethodNode method = named(read(owner), "findShip");
        method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        node.methods.add(method);
        Map<String, String> names = new HashMap<>();
        names.put(owner, node.name);
        for (String name : List.of("net/minecraft/server/level/ServerLevel", "net/minecraft/client/multiplayer/ClientLevel")) {
            names.put(name, Type.getInternalName(World.class));
        }
        for (String name : List.of(CONTAINER, "dev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer",
            "dev/ryanhcode/sable/api/sublevel/ClientSubLevelContainer")) {
            names.put(name, Type.getInternalName(Container.class));
        }
        names.put("net/minecraft/server/MinecraftServer", Type.getInternalName(Worlds.class));
        names.put("qouteall/imm_ptl/core/ClientWorldLoader", Type.getInternalName(Worlds.class));
        names.put("dev/ryanhcode/sable/sublevel/SubLevel", Type.getInternalName(Ship.class));
        names.put("dev/ryanhcode/sable/sublevel/ServerSubLevel", Type.getInternalName(ServerShip.class));
        names.put("dev/ryanhcode/sable/sublevel/ClientSubLevel", Type.getInternalName(ClientShip.class));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new SimpleRemapper(names)));
        class Loader extends ClassLoader {
            Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        }
        Class<?> fixture = new Loader().define(writer.toByteArray());
        return new Lookup(client, fixture.getMethod("findShip", client
            ? new Class<?>[]{UUID.class} : new Class<?>[]{Worlds.class, UUID.class}));
    }

    private static void assertRemovedGuard(String owner, String methodPrefix) throws Exception {
        MethodNode method = read(owner).methods.stream().filter(m -> m.name.startsWith(methodPrefix)
            && calls(m, owner, "findShip")).findFirst().orElseThrow();
        var instruction = call(method, "findShip").getNext();
        while (instruction != null) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("isRemoved")
                && call.owner.startsWith("dev/ryanhcode/sable/sublevel/")) return;
            instruction = instruction.getNext();
        }
        fail("Caller must retain removal handling after lookup: " + method.name);
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
            && call.owner.equals(owner) && call.name.equals(name)) return true;
        return false;
    }
    private static MethodInsnNode call(MethodNode method, String name) {
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
            && call.name.equals(name)) return call;
        throw new AssertionError("Missing call " + method.name + " -> " + name);
    }
    private static MethodNode named(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static ClassNode read(String name) throws Exception {
        ClassNode node = new ClassNode();
        try (var input = IplShipLookupTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(input, name);
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }
}
