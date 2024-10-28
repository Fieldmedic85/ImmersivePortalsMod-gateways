package qouteall.imm_ptl.core.block_manipulation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.portal.PortalUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.function.Supplier;

public class BlockManipulationClient {
    private static final Minecraft client = Minecraft.getInstance();

    public static ResourceKey<Level> remotePointedDim;
    public static HitResult remoteHitResult;

    private static final int TICK_INTERVAL = 5; // Update every 5 ticks
    private static int tickCounter = 0;

    private static Vec3 cachedCameraPos;
    private static Vec3 cachedViewVector;

    // Register client tick event for lazy updating
    static {
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (tickCounter++ % TICK_INTERVAL == 0 && client.player != null) {
                updatePointedBlock(1.0F); // Partial tick for smoothness
            }
        });
    }

    public static boolean isPointingToPortal() {
        return remotePointedDim != null;
    }

    @Nullable
    public static ClientLevel getRemotePointedWorld() {
        return remotePointedDim == null ? null : ClientWorldLoader.getWorld(remotePointedDim);
    }

    private static BlockHitResult createMissedHitResult(Vec3 from, Vec3 to) {
        Vec3 dir = to.subtract(from).normalize();
        return BlockHitResult.miss(to, Direction.getNearest(dir.x, dir.y, dir.z), BlockPos.containing(to));
    }

    private static double getCurrentTargetDistance() {
        Vec3 cameraPos = cachedCameraPos;
        if (client.hitResult == null || client.hitResult.getType() == HitResult.Type.MISS) return Double.MAX_VALUE;

        if (client.hitResult instanceof BlockHitResult blockHitResult) {
            BlockPos hitPos = blockHitResult.getBlockPos();
            if (client.level.getBlockState(hitPos).getBlock() == PortalPlaceholderBlock.instance) return Double.MAX_VALUE;
        }

        return cameraPos.distanceTo(client.hitResult.getLocation());
    }

    public static void updatePointedBlock(float partialTick) {
        if (client.gameMode == null || client.level == null || client.player == null) return;

        // Avoid unnecessary recalculations by checking if player moved or changed view direction
        Vec3 currentCameraPos = client.gameRenderer.getMainCamera().getPosition();
        Vec3 currentViewVector = client.player.getViewVector(partialTick);
        if (currentCameraPos.equals(cachedCameraPos) && currentViewVector.equals(cachedViewVector)) return;

        cachedCameraPos = currentCameraPos;
        cachedViewVector = currentViewVector;

        remotePointedDim = null;
        remoteHitResult = null;

        if (!BlockManipulationServer.canDoCrossPortalInteractionEvent.invoker().test(client.player)) return;

        client.getProfiler().push("portalInteraction");

        double reachDistance = client.player.blockInteractionRange();
        PortalUtils.raytracePortalFromEntityView(client.player, partialTick, reachDistance, true, 
            portal -> portal.isInteractableBy(client.player)).ifPresent(pair -> {
                Portal portal = pair.getFirst();
                Vec3 hitPos = pair.getSecond().hitPos();
                double distanceToPortalPointing = hitPos.distanceTo(currentCameraPos);

                if (distanceToPortalPointing < getCurrentTargetDistance() + 0.2) {
                    client.hitResult = createMissedHitResult(currentCameraPos, hitPos);
                    updateTargetedBlockThroughPortal(currentCameraPos, currentViewVector, client.player.level().dimension(),
                                                     distanceToPortalPointing, reachDistance, portal);
                }
            });

        client.getProfiler().pop();
    }

    private static void updateTargetedBlockThroughPortal(
        Vec3 cameraPos,
        Vec3 viewVector,
        ResourceKey<Level> playerDimension,
        double beginDistance,
        double endDistance,
        Portal portal
    ) {
        Vec3 from = portal.transformPoint(cameraPos.add(viewVector.scale(beginDistance)));
        Vec3 to = portal.transformPoint(cameraPos.add(viewVector.scale(endDistance)));

        ClipContext context = new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player);
        ClientLevel world = ClientWorldLoader.getWorld(portal.getDestDim());

        remoteHitResult = BlockGetter.traverseBlocks(from, to, context,
            (rayTraceContext, blockPos) -> {
                BlockState blockState = world.getBlockState(blockPos);
                if (blockState.getBlock() == PortalPlaceholderBlock.instance || blockState.getBlock() == Blocks.BARRIER) return null;

                Vec3 start = rayTraceContext.getFrom().subtract(rayTraceContext.getTo().scale(0.0015));
                VoxelShape solidShape = rayTraceContext.getBlockShape(blockState, world, blockPos);
                BlockHitResult blockHitResult = world.clipWithInteractionOverride(start, rayTraceContext.getTo(), blockPos, solidShape, blockState);

                FluidState fluidState = world.getFluidState(blockPos);
                VoxelShape fluidShape = rayTraceContext.getFluidShape(fluidState, world, blockPos);
                BlockHitResult fluidHitResult = fluidShape.clip(rayTraceContext.getFrom(), rayTraceContext.getTo(), blockPos);

                double d = blockHitResult == null ? Double.MAX_VALUE : start.distanceToSqr(blockHitResult.getLocation());
                double e = fluidHitResult == null ? Double.MAX_VALUE : start.distanceToSqr(fluidHitResult.getLocation());
                return d <= e ? blockHitResult : fluidHitResult;
            },
            (rayTraceContext) -> createMissedHitResult(from, to)
        );

        if (remoteHitResult instanceof BlockHitResult hitResult && hitResult.getLocation().y < world.getMinBuildHeight() + 0.1) {
            remoteHitResult = new BlockHitResult(hitResult.getLocation(), Direction.DOWN, hitResult.getBlockPos(), hitResult.isInside());
        }

        if (remoteHitResult != null && !world.getBlockState(((BlockHitResult) remoteHitResult).getBlockPos()).isAir()) {
            client.hitResult = createMissedHitResult(from, to);
            remotePointedDim = portal.getDestDim();
        }
    }

    public static <T> T withSwitchedContext(Supplier<T> func, boolean transformHitResult) {
        Validate.notNull(remoteHitResult);
        ClientLevel remoteWorld = getRemotePointedWorld();
        Validate.notNull(remoteWorld);

        HitResult effectiveHitResult = transformHitResult && (remoteHitResult instanceof BlockHitResult blockHitResult) 
            ? BlockManipulationServer.getHitResultForPlacing(remoteWorld, blockHitResult).getA() 
            : remoteHitResult;

        return ClientWorldLoader.withSwitchedWorld(remoteWorld, () -> {
            HitResult originalHitResult = client.hitResult;
            client.hitResult = effectiveHitResult;
            try {
                return func.get();
            } finally {
                client.hitResult = originalHitResult;
            }
        });
    }

    @Nullable
    public static String getDebugString() {
        if (remotePointedDim == null || !(remoteHitResult instanceof BlockHitResult blockHitResult)) return null;
        return "Point:%s %d %d %d".formatted(remotePointedDim.location(), blockHitResult.getBlockPos().getX(),
                                              blockHitResult.getBlockPos().getY(), blockHitResult.getBlockPos().getZ());
    }
}