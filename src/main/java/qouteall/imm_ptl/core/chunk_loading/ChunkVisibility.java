package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.my_util.LimitedLogger;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class ChunkVisibility {
    private static final LimitedLogger limitedLogger = new LimitedLogger(10);
    private static final int PORTAL_LOADING_RANGE = 48;
    public static final int SECONDARY_PORTAL_LOADING_RANGE = 16;

    public static ChunkLoader playerDirectLoader(ServerPlayer player) {
        return new ChunkLoader(
            new DimensionalChunkPos(
                player.level().dimension(),
                player.chunkPosition()
            ),
            McHelper.getPlayerLoadDistance(player)
        );
    }

    private static int getDirectLoadingDistance(int renderDistance, double distanceToPortal) {
        if (distanceToPortal < 5) {
            return renderDistance;
        } else if (distanceToPortal < 15) {
            return (renderDistance * 2) / 3;
        } else {
            return renderDistance / 3;
        }
    }

    private static int getCappedLoadingDistance(Portal portal, ServerPlayer player, int targetLoadingDistance) {
        PerformanceLevel performanceLevel = ImmPtlChunkTracking.getPlayerInfo(player).performanceLevel;
        int cap = Math.min(
            PerformanceLevel.getIndirectLoadingRadiusCap(performanceLevel),
            IPGlobal.indirectLoadingRadiusCap
        );

        // Allow additional loading for scaling portals
        if (portal.getScale() > 2) {
            cap *= 2;
        }
        return Math.min(targetLoadingDistance, cap);
    }

    public static List<Portal> getNearbyPortals(
        ServerLevel world, Vec3 pos, Predicate<Portal> predicate, int radiusChunks, int radiusChunksForGlobalPortals
    ) {
        List<Portal> result = McHelper.findEntitiesRough(
            Portal.class, world, pos, radiusChunks, predicate
        );

        GlobalPortalStorage.getGlobalPortals(world).stream()
            .filter(globalPortal -> globalPortal.getDistanceToNearestPointInPortal(pos) < radiusChunksForGlobalPortals * 16)
            .forEach(result::add);

        if (result.size() > 100) {
            limitedLogger.err("Too many portals nearby " + world + pos);
            return List.of(result.stream().min(Comparator.comparingDouble(p -> p.getDistanceToNearestPointInPortal(pos))).orElseThrow());
        }
        return result;
    }

    private static ChunkLoader getGeneralDirectPortalLoader(ServerPlayer player, Portal portal) {
        int loadDistance = McHelper.getPlayerLoadDistance(player);
        double distance = portal.getDistanceToNearestPointInPortal(player.position());

        if (portal.getIsGlobal()) {
            int renderDistance = Math.min(
                IPGlobal.indirectLoadingRadiusCap * 2,
                Math.max(2, loadDistance - (int) Math.floor(distance / 16))
            );
            return new ChunkLoader(
                new DimensionalChunkPos(portal.getDestDim(), new ChunkPos(BlockPos.containing(portal.transformPoint(player.position())))),
                renderDistance
            );
        } else {
            if (portal.getScaling() > 2 && distance < 5) {
                loadDistance = (int) ((portal.getDestAreaRadiusEstimation() * 1.4) / 16);
            }
            return new ChunkLoader(
                new DimensionalChunkPos(portal.getDestDim(), new ChunkPos(BlockPos.containing(portal.getDestPos()))),
                getCappedLoadingDistance(portal, player, getDirectLoadingDistance(loadDistance, distance))
            );
        }
    }

    private static ChunkLoader getGeneralPortalIndirectLoader(ServerPlayer player, Vec3 transformedPos, Portal portal) {
        int loadDistance = McHelper.getPlayerLoadDistance(player);

        if (portal.getIsGlobal()) {
            return new ChunkLoader(
                new DimensionalChunkPos(portal.getDestDim(), new ChunkPos(BlockPos.containing(transformedPos))),
                Math.min(IPGlobal.indirectLoadingRadiusCap, loadDistance / 3)
            );
        } else {
            return new ChunkLoader(
                new DimensionalChunkPos(portal.getDestDim(), new ChunkPos(BlockPos.containing(portal.getDestPos()))),
                getCappedLoadingDistance(portal, player, loadDistance / 4)
            );
        }
    }

    public static void foreachBaseChunkLoaders(ServerPlayer player, Consumer<ChunkLoader> func) {
        PerformanceLevel perfLevel = ImmPtlChunkTracking.getPlayerInfo(player).performanceLevel;
        int visiblePortalRangeChunks = PerformanceLevel.getVisiblePortalRangeChunks(perfLevel);
        int indirectVisiblePortalRangeChunks = PerformanceLevel.getIndirectVisiblePortalRangeChunks(perfLevel);

        func.accept(playerDirectLoader(player));

        List<Portal> nearbyPortals = getNearbyPortals(
            (ServerLevel) player.level(),
            player.position(),
            portal -> portal.broadcastToPlayer(player),
            visiblePortalRangeChunks, 256
        );

        for (Portal portal : nearbyPortals) {
            Level destinationWorld = portal.getDestinationWorld();
            if (destinationWorld == null) continue;

            Vec3 transformedPlayerPos = portal.transformPoint(player.position());
            func.accept(getGeneralDirectPortalLoader(player, portal));

            if (!isShrinkLoading()) {
                List<Portal> indirectNearbyPortals = getNearbyPortals(
                    (ServerLevel) destinationWorld,
                    transformedPlayerPos,
                    p -> p.broadcastToPlayer(player),
                    indirectVisiblePortalRangeChunks, 32
                );

                for (Portal innerPortal : indirectNearbyPortals) {
                    func.accept(getGeneralPortalIndirectLoader(player, transformedPlayerPos, innerPortal));
                }
            }
        }
    }

    public static boolean isShrinkLoading() {
        return ServerPerformanceMonitor.getLevel() != PerformanceLevel.good;
    }
}