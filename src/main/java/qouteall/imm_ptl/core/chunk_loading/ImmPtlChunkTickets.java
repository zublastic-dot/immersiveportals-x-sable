package qouteall.imm_ptl.core.chunk_loading;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.DimensionEvents;
import de.nick1st.imm_ptl.events.ServerCleanupEvent;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongPredicate;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IEServerChunkCache;
import qouteall.imm_ptl.core.ducks.IEWorld;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.RateStat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Each {@link ImmPtlChunkTickets} manages ImmPtl chunk ticket for one dimension.
 * <p>
 * It re-implements player chunk loading throttling which is much simpler than vanilla's.
 * In vanilla, each chunk that get loaded by player has a chunk ticket.
 * The chunk tickets are not added immediately, but added by a throttled mechanism.
 * The throttling will reduce the world generation and chunk loading workload when the player moves fast,
 * and prioritize the chunks near player.
 * <p>
 * In vanilla, it uses {@link ChunkTaskPriorityQueue} that has 4 slots of "acquired" chunk positions.
 * If the acquired chunk slots are full, it will stop processing task, until a slot releases.
 * The {@link ChunkTaskPriorityQueueSorter} uses a {@link ProcessorMailbox}
 * (the mailbox is similar to a one-thread thread pool but uses threads from the worker thread pool)
 * to do a lot of message-passing (it enqueues at least 5 messages just to add one ticket).
 * In {@link DistanceManager.PlayerTicketTracker} it sends message for acquiring and releasing.
 * The chunk positions to release are passed into {@link DistanceManager#ticketsToRelease}.
 * A callback for sending message for releasing will be added to these chunk's future.
 */
@SuppressWarnings("JavadocReference")
public class ImmPtlChunkTickets {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static final TicketType<ChunkPos> TICKET_TYPE =
        TicketType.create("imm_ptl", Comparator.comparingLong(ChunkPos::toLong));
    
    // for debugging
    @SuppressWarnings("FieldMayBeFinal")
    private static boolean enableDebugRateStat = false;
    private static final RateStat debugRateStat = new RateStat("imm_ptl_chunk_ticket");
    
    // the fields of ImmPtlChunkTickets should avoid referencing ServerLevel
    public static final WeakHashMap<ServerLevel, ImmPtlChunkTickets> BY_DIMENSION = new WeakHashMap<>();
    
    public static void init() {
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
            event -> ChunkPacketDiagnostics.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(DimensionEvents.BeforeRemovingDimensionEvent.class,
                beforeRemovingDimensionEvent -> ImmPtlChunkTickets.onDimensionRemove(beforeRemovingDimensionEvent.dimension));

        NeoForge.EVENT_BUS.addListener(ServerCleanupEvent.class, ImmPtlChunkTickets::cleanup);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event ->
            event.getDispatcher().register(Commands.literal("imm_ptl_chunk_tickets")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    for (ServerLevel level : context.getSource().getServer().getAllLevels()) {
                        ImmPtlChunkTickets manager = BY_DIMENSION.get(level);
                        if (manager != null) {
                            context.getSource().sendSuccess(() -> Component.literal(
                                level.dimension().location() + " " + manager.describe()
                            ), false);
                        }
                    }
                    return 1;
                })));
    }
    
    public static class ChunkTicketInfo {
        public int lastUpdateGeneration;
        public int distanceToSource;
        // Zero means queued, with no ticket admitted yet. Never use a later config
        // value to remove an existing ticket or choose the future it requested.
        int loadingRadius;
        long startedNanos;
        long nextStallReportNanos;
        
        public ChunkTicketInfo(int lastUpdateGeneration, int distanceToSource) {
            this.lastUpdateGeneration = lastUpdateGeneration;
            this.distanceToSource = distanceToSource;
        }
    }
    
    private final Long2ObjectOpenHashMap<ChunkTicketInfo> chunkPosToTicketInfo = new Long2ObjectOpenHashMap<>();
    
    private final ArrayList<LongLinkedOpenHashSet> chunksToAddTicketByDistance = new ArrayList<>();
    
    private final LongOpenHashSet waitingForLoading = new LongOpenHashSet();
    
    private boolean isValid = true;
    
    public final int throttlingLimit = 4;

    static final long STALL_REPORT_INTERVAL = TimeUnit.SECONDS.toNanos(60);
    private long admittedCount;
    private long completedCount;
    private long failedCount;
    private long stallReportCount;
    
    ImmPtlChunkTickets() {
    
    }
    
    // it takes in world instead of dimension intId, to ensure dimension really exists
    public static ImmPtlChunkTickets get(ServerLevel world) {
        return BY_DIMENSION.computeIfAbsent(world, k -> new ImmPtlChunkTickets());
    }
    
    public void markForLoading(long chunkPos, int distanceToSource, int generation) {
        Validate.isTrue(distanceToSource >= 0);
        
        ChunkTicketInfo info = chunkPosToTicketInfo.get(chunkPos);
        
        if (info == null) {
            info = new ChunkTicketInfo(generation, distanceToSource);
            chunkPosToTicketInfo.put(chunkPos, info);
            getQueueByDistance(distanceToSource).add(chunkPos);
        }
        else {
            if (generation != info.lastUpdateGeneration) {
                info.lastUpdateGeneration = generation;
                int oldDistanceToSource = info.distanceToSource;
                info.distanceToSource = distanceToSource;
                if (getQueueByDistance(oldDistanceToSource).remove(chunkPos)) {
                    getQueueByDistance(distanceToSource).add(chunkPos);
                }
            }
            else {
                if (distanceToSource < info.distanceToSource) {
                    int oldDistanceToSource = info.distanceToSource;
                    info.distanceToSource = distanceToSource;
                    if (getQueueByDistance(oldDistanceToSource).remove(chunkPos)) {
                        getQueueByDistance(distanceToSource).add(chunkPos);
                    }
                }
            }
        }
    }
    
    private LongLinkedOpenHashSet getQueueByDistance(int distanceToSource) {
        return Helper.arrayListComputeIfAbsent(
            chunksToAddTicketByDistance,
            distanceToSource,
            LongLinkedOpenHashSet::new
        );
    }
    
    public void tick(ServerLevel world) {
        flushThrottling(world);
    }
    
    /**
     * This method is called during ticking and {@link DistanceManager#runAllUpdates(ChunkMap)} .
     * <p>
     * Only calling this method during ticking will make it throttled too slow.
     * <p>
     * This method uses the chunk holder's future, so it should be called after
     * {@link DistanceManager#runAllUpdates(ChunkMap)}
     * (as it calls {@link ChunkHolder#updateFutures(ChunkMap, Executor)}).
     * Before promotion, the holder can return {@link ChunkHolder#UNLOADED_LEVEL_CHUNK}.
     * C2ME also exposes that sentinel while its status/ticket changes propagate.
     * It is not evidence of a completed generation failure.
     * Each task to {@link net.minecraft.server.level.ServerChunkCache.MainThreadExecutor} will trigger
     * {@link DistanceManager#runAllUpdates(ChunkMap)}.
     */
    public void flushThrottling(ServerLevel world) {
        if (Thread.currentThread() != ((IEWorld) world).portal_getThread()) {
            LOGGER.error("Called in a non-server-main (or server-world) thread.", new Throwable());
            return;
        }
        
        if (enableDebugRateStat) {
            debugRateStat.update();
        }
        
        if (!isValid) {
            LOGGER.error("flushing when invalid {}", world);
            return;
        }
        
        if (!world.getServer().isRunning()) {
            // important: don't add chunk ticket when server is saving
            // https://github.com/iPortalTeam/ImmersivePortalsMod/issues/1455
            return;
        }
        
        flushThrottling(new WorldTicketAccess(world), System.nanoTime(),
            getLoadingRadius(), IPConfig.getConfig().enableImmPtlChunkLoading);
    }

    // The same scheduler is used by the server and the deterministic regression
    // harness. The access object is short-lived; it must not retain a level here.
    void flushThrottling(TicketAccess access, long now, int loadingRadius, boolean enabled) {
        if (!isValid || !enabled) {
            return;
        }

        waitingForLoading.removeIf((long chunkPos) -> {
            ChunkTicketInfo info = chunkPosToTicketInfo.get(chunkPos);
            if (info == null) {
                return true;
            }

            ChunkLoadObservation observation = access.poll(chunkPos, info.loadingRadius);
            if (observation.state() == ChunkLoadObservation.State.WAITING) {
                if (now - info.nextStallReportNanos >= 0) {
                    stallReportCount++;
                    access.reportStall(chunkPos, info.loadingRadius,
                        TimeUnit.NANOSECONDS.toSeconds(now - info.startedNanos));
                    info.nextStallReportNanos = now + STALL_REPORT_INTERVAL;
                }
                return false;
            }

            if (observation.state() == ChunkLoadObservation.State.FAILED) {
                failedCount++;
                access.reportFailure(chunkPos, info.loadingRadius, observation);
            }
            else {
                completedCount++;
            }
            return true;
        });
        
        // flush the pending-add-ticket queues
        for (LongLinkedOpenHashSet queue : chunksToAddTicketByDistance) {
            if (queue != null) {
                while (!queue.isEmpty()) {
                    if (waitingForLoading.size() >= throttlingLimit) {
                        return;
                    }
                    
                    long chunkPos = queue.removeFirstLong();
                    ChunkTicketInfo info = chunkPosToTicketInfo.get(chunkPos);
                    if (info != null) {
                        access.add(chunkPos, loadingRadius);
                        info.loadingRadius = loadingRadius;
                        info.startedNanos = now;
                        info.nextStallReportNanos = now + STALL_REPORT_INTERVAL;
                        admittedCount++;
                        waitingForLoading.add(chunkPos);
                    }
                    else {
                        LOGGER.warn("Chunk {} is not in the queue", new ChunkPos(chunkPos));
                    }
                }
            }
        }
    }
    
    public void purge(
        ServerLevel world,
        LongPredicate shouldKeepLoadingFunc
    ) {
        purge(new WorldTicketAccess(world), shouldKeepLoadingFunc);
    }

    void purge(TicketAccess access, LongPredicate shouldKeepLoadingFunc) {
        chunkPosToTicketInfo.long2ObjectEntrySet().removeIf(e -> {
            long chunkPos = e.getLongKey();
            ChunkTicketInfo ticketInfo = e.getValue();
            
            boolean keepLoading = shouldKeepLoadingFunc.test(chunkPos);
            
            if (!keepLoading) {
                waitingForLoading.remove(chunkPos);
                
                getQueueByDistance(ticketInfo.distanceToSource).remove(chunkPos);

                if (ticketInfo.loadingRadius != 0) {
                    access.remove(chunkPos, ticketInfo.loadingRadius);
                }
                return true;
            }
            else {
                return false;
            }
        });
    }
    
    public int getLoadedChunkNum() {
        return chunkPosToTicketInfo.size();
    }
    
    public static void onDimensionRemove(ServerLevel world) {
        ImmPtlChunkTickets dimTicketManager = BY_DIMENSION.remove(world);
        
        if (dimTicketManager == null) {
            return;
        }
        
        dimTicketManager.invalidate(new WorldTicketAccess(world));
    }

    void invalidate(TicketAccess access) {
        // removeRegionTicket takes a radius, NOT the absolute ticket level.
        purge(access, pos -> false);
        isValid = false;
    }

    String describe() {
        int queued = chunksToAddTicketByDistance.stream().mapToInt(q -> q == null ? 0 : q.size()).sum();
        return "tracked=" + chunkPosToTicketInfo.size() + " queued=" + queued +
            " waiting=" + waitingForLoading.size() + "/" + throttlingLimit +
            " admitted=" + admittedCount + " completed=" + completedCount +
            " failed=" + failedCount + " stall_reports=" + stallReportCount;
    }

    interface TicketAccess {
        void add(long pos, int radius);
        void remove(long pos, int radius);
        ChunkLoadObservation poll(long pos, int radius);
        void reportFailure(long pos, int radius, ChunkLoadObservation result);
        void reportStall(long pos, int radius, long seconds);
    }

    private record WorldTicketAccess(ServerLevel world) implements TicketAccess {
        @Override
        public void add(long pos, int radius) {
            ChunkPos chunkPos = new ChunkPos(pos);
            getDistanceManager(world).addRegionTicket(TICKET_TYPE, chunkPos, radius, chunkPos);
            if (enableDebugRateStat) {
                debugRateStat.hit();
            }
        }

        @Override
        public void remove(long pos, int radius) {
            ChunkPos chunkPos = new ChunkPos(pos);
            getDistanceManager(world).removeRegionTicket(TICKET_TYPE, chunkPos, radius, chunkPos);
        }

        @Override
        public ChunkLoadObservation poll(long pos, int radius) {
            ChunkHolder holder = getChunkHolder(world, pos);
            // A ticket can exist before its holder is published to the visible map.
            if (holder == null) {
                return ChunkLoadObservation.WAITING;
            }
            return ChunkLoadObservation.poll(radius >= 2 ? holder.getEntityTickingChunkFuture() :
                holder.getTickingChunkFuture(), ChunkHolder.UNLOADED_LEVEL_CHUNK);
        }

        @Override
        public void reportFailure(long pos, int radius, ChunkLoadObservation result) {
            LOGGER.error("Chunk loading failure world={} chunk={} radius={} reason={}",
                world.dimension().location(), new ChunkPos(pos), radius, result.error(), result.cause());
        }

        @Override
        public void reportStall(long pos, int radius, long seconds) {
            ChunkHolder holder = getChunkHolder(world, pos);
            LOGGER.warn("Chunk loading still pending world={} chunk={} radius={} age={}s holder={} ticket_level={}",
                world.dimension().location(), new ChunkPos(pos), radius, seconds,
                holder == null ? "absent" : holder.getClass().getName(),
                holder == null ? "unknown" : holder.getTicketLevel());
        }
    }
    
    public static int getLoadingRadius() {
        if (IPGlobal.activeLoading) {
            return 2;
        }
        else {
            return 1;
        }
    }
    
    public static ChunkHolder getChunkHolder(ServerLevel world, long chunkPos) {
        return ((IEChunkMap) (world.getChunkSource()).chunkMap).ip_getChunkHolder(chunkPos);
    }
    
    public static DistanceManager getDistanceManager(ServerLevel world) {
        return ((IEServerChunkCache) world.getChunkSource()).ip_getDistanceManager();
    }
    
    private static void cleanup(ServerCleanupEvent event) {
        for (ImmPtlChunkTickets immPtlChunkTickets : BY_DIMENSION.values()) {
            immPtlChunkTickets.isValid = false;
        }
        BY_DIMENSION.clear();
    }
}
