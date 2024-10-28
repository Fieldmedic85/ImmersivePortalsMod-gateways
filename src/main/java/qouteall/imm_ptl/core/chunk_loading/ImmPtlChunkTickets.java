package qouteall.imm_ptl.core.chunk_loading;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongPredicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IEDistanceManager;
import qouteall.imm_ptl.core.ducks.IEServerChunkCache;
import qouteall.imm_ptl.core.ducks.IEWorld;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.RateStat;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

public class ImmPtlChunkTickets {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static final TicketType<ChunkPos> TICKET_TYPE =
        TicketType.create("imm_ptl", Comparator.comparingLong(ChunkPos::toLong));
    
    private static final RateStat debugRateStat = new RateStat("imm_ptl_chunk_ticket");
    public static final WeakHashMap<ServerLevel, ImmPtlChunkTickets> BY_DIMENSION = new WeakHashMap<>();
    
    public static void init() {
        DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT.register(ImmPtlChunkTickets::onDimensionRemove);
        IPGlobal.SERVER_CLEANUP_EVENT.register(ImmPtlChunkTickets::cleanup);
    }
    
    public static class ChunkTicketInfo {
        public int lastUpdateGeneration;
        public int distanceToSource;
        
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

    private ImmPtlChunkTickets() {}
    
    public static ImmPtlChunkTickets get(ServerLevel world) {
        return BY_DIMENSION.computeIfAbsent(world, k -> new ImmPtlChunkTickets());
    }
    
    public void markForLoading(long chunkPos, int distanceToSource, int generation) {
        ChunkTicketInfo info = chunkPosToTicketInfo.get(chunkPos);
        
        if (info == null) {
            info = new ChunkTicketInfo(generation, distanceToSource);
            chunkPosToTicketInfo.put(chunkPos, info);
            getQueueByDistance(distanceToSource).add(chunkPos);
        } else {
            if (generation != info.lastUpdateGeneration || distanceToSource < info.distanceToSource) {
                updateTicketInfo(chunkPos, distanceToSource, generation, info);
            }
        }
    }

    private void updateTicketInfo(long chunkPos, int distanceToSource, int generation, ChunkTicketInfo info) {
        int oldDistanceToSource = info.distanceToSource;
        info.lastUpdateGeneration = generation;
        info.distanceToSource = distanceToSource;
        if (getQueueByDistance(oldDistanceToSource).remove(chunkPos)) {
            getQueueByDistance(distanceToSource).add(chunkPos);
        }
    }
    
    private LongLinkedOpenHashSet getQueueByDistance(int distanceToSource) {
        return Helper.arrayListComputeIfAbsent(
            chunksToAddTicketByDistance, distanceToSource, LongLinkedOpenHashSet::new
        );
    }
    
    public void tick(ServerLevel world) {
        flushThrottling(world);
    }
    
    public void flushThrottling(ServerLevel world) {
        if (Thread.currentThread() != ((IEWorld) world).portal_getThread()) {
            LOGGER.error("Called in a non-server-main thread.", new Throwable());
            return;
        }
        
        debugRateStat.update();
        
        if (!isValid || !world.getServer().isRunning()) {
            return;
        }
        
        DistanceManager distanceManager = getDistanceManager(world);
        
        waitingForLoading.removeIf((long chunkPos) -> {
            ChunkHolder chunkHolder = getChunkHolder(world, chunkPos);
            if (chunkHolder == null) {
                return true;
            }
            ChunkResult<LevelChunk> resultNow = chunkHolder.getEntityTickingChunkFuture().getNow(null);
            return resultNow == null || !resultNow.isSuccess();
        });
        
        for (LongLinkedOpenHashSet queue : chunksToAddTicketByDistance) {
            if (queue != null) {
                while (!queue.isEmpty() && waitingForLoading.size() < throttlingLimit) {
                    long chunkPos = queue.removeFirstLong();
                    if (chunkPosToTicketInfo.containsKey(chunkPos)) {
                        addTicket(distanceManager, chunkPos);
                        waitingForLoading.add(chunkPos);
                    } else {
                        LOGGER.warn("Chunk {} is not in the queue", new ChunkPos(chunkPos));
                    }
                }
            }
        }
    }
    
    private static void addTicket(DistanceManager distanceManager, long chunkPos) {
        if (!IPConfig.getConfig().enableImmPtlChunkLoading) {
            return;
        }
        
        ChunkPos chunkPosObj = new ChunkPos(chunkPos);
        distanceManager.addRegionTicket(TICKET_TYPE, chunkPosObj, getLoadingRadius(), chunkPosObj);
        debugRateStat.hit();
    }
    
    public void purge(ServerLevel world, LongPredicate shouldKeepLoadingFunc) {
        DistanceManager distanceManager = getDistanceManager(world);
        
        chunkPosToTicketInfo.long2ObjectEntrySet().removeIf(e -> {
            long chunkPos = e.getLongKey();
            if (!shouldKeepLoadingFunc.test(chunkPos)) {
                waitingForLoading.remove(chunkPos);
                if (!getQueueByDistance(e.getValue().distanceToSource).remove(chunkPos)) {
                    distanceManager.removeRegionTicket(TICKET_TYPE, new ChunkPos(chunkPos), getLoadingRadius(), new ChunkPos(chunkPos));
                }
                return true;
            }
            return false;
        });
    }
    
    public int getLoadedChunkNum() {
        return chunkPosToTicketInfo.size();
    }
    
    public static void onDimensionRemove(ServerLevel world) {
        ImmPtlChunkTickets dimTicketManager = BY_DIMENSION.remove(world);
        if (dimTicketManager != null) {
            removeAllTicketsInWorld(world, dimTicketManager);
        }
    }
    
    private static void removeAllTicketsInWorld(ServerLevel world, ImmPtlChunkTickets dimTicketManager) {
        DistanceManager ticketManager = getDistanceManager(world);
        
        dimTicketManager.chunkPosToTicketInfo.keySet().forEach((long pos) -> {
            SortedArraySet<Ticket<?>> tickets = ((IEDistanceManager) getDistanceManager(world)).portal_getTicketSet(pos);
            List<Ticket<?>> toRemove = tickets.stream()
                .filter(t -> t.getType() == TICKET_TYPE).toList();
            
            ChunkPos chunkPos = new ChunkPos(pos);
            for (Ticket<?> ticket : toRemove) {
                ticketManager.removeRegionTicket(TICKET_TYPE, chunkPos, ticket.getTicketLevel(), chunkPos);
            }
        });
        
        dimTicketManager.isValid = false;
    }
    
    public static int getLoadingRadius() {
        return IPGlobal.activeLoading ? 2 : 1;
    }
    
    public static ChunkHolder getChunkHolder(ServerLevel world, long chunkPos) {
        return ((IEChunkMap) (world.getChunkSource()).chunkMap).ip_getChunkHolder(chunkPos);
    }
    
    public static DistanceManager getDistanceManager(ServerLevel world) {
        return ((IEServerChunkCache) world.getChunkSource()).ip_getDistanceManager();
    }
    
    private static void cleanup(MinecraftServer server) {
        BY_DIMENSION.values().forEach(dimTicketManager -> dimTicketManager.isValid = false);
        BY_DIMENSION.clear();
    }
}