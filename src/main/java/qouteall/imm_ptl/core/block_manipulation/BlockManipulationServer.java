package qouteall.imm_ptl.core.block_manipulation;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.ScaleUtils;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.List;
import java.util.function.Predicate;

@SuppressWarnings("resource")
public class BlockManipulationServer {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static record Context(
        ServerLevel world,
        @Nullable BlockHitResult blockHitResult
    ) {}

    public static final ThreadLocal<Context> REDIRECT_CONTEXT = ThreadLocal.withInitial(() -> null);

    public static final Event<Predicate<Player>> canDoCrossPortalInteractionEvent =
        EventFactory.createArrayBacked(Predicate.class,
            handlers -> player -> {
                for (Predicate<Player> handler : handlers) {
                    if (!handler.test(player)) {
                        return false;
                    }
                }
                return true;
            });

    private static boolean canPlayerReach(
        ResourceKey<Level> dimension,
        ServerPlayer player,
        BlockPos requestPos
    ) {
        if (!canDoCrossPortalInteractionEvent.invoker().test(player)) return false;

        double playerScale = ScaleUtils.computeBlockReachScale(player);
        double reachDistanceSquared = 144 * playerScale * playerScale;
        Vec3 playerPos = player.position();
        Vec3 pos = Vec3.atCenterOf(requestPos);

        if (player.level().dimension() == dimension && playerPos.distanceToSqr(pos) < reachDistanceSquared) {
            return true;
        }

        return IPMcHelper.getNearbyPortals(player, IPGlobal.maxNormalPortalRadius)
            .anyMatch(portal ->
                portal.getDestDim() == dimension &&
                portal.isInteractableBy(player) &&
                portal.transformPoint(playerPos).distanceToSqr(pos) <
                    reachDistanceSquared * portal.getScale() * portal.getScale()
            );
    }

    public static Tuple<BlockHitResult, ResourceKey<Level>> getHitResultForPlacing(
        Level world,
        BlockHitResult blockHitResult
    ) {
        Direction side = blockHitResult.getDirection();
        Vec3 sideVec = Vec3.atLowerCornerOf(side.getNormal());
        BlockPos hitPos = blockHitResult.getBlockPos();
        Vec3 hitCenter = Vec3.atCenterOf(hitPos);

        return GlobalPortalStorage.getGlobalPortals(world).stream()
            .filter(p ->
                p.getNormal().dot(sideVec) < -0.9 &&
                p.getPortalShape().isBoxInPortalProjection(p.getThisSideState(), new AABB(hitPos)) &&
                p.getDistanceToPlane(hitCenter) < 0.6
            )
            .findFirst()
            .map(portal -> {
                Vec3 newCenter = portal.transformPoint(hitCenter.add(sideVec.scale(0.501)));
                BlockPos placingBlockPos = BlockPos.containing(newCenter);
                BlockHitResult newHitResult = new BlockHitResult(
                    Vec3.ZERO, side.getOpposite(), placingBlockPos, blockHitResult.isInside()
                );
                return new Tuple<>(newHitResult, portal.getDestDim());
            })
            .orElse(new Tuple<>(blockHitResult, world.dimension()));
    }

    public static class RemoteCallables {
        
        public static void processPlayerActionPacket(ServerPlayer player, ResourceKey<Level> dimension, byte[] packetBytes) {
            ServerLevel world = getServerWorld(player, dimension);
            if (world == null) return;

            ServerboundPlayerActionPacket packet = decodePacket(packetBytes, ServerboundPlayerActionPacket.STREAM_CODEC);
            withRedirect(new Context(world, null), () -> doProcessPlayerAction(world, player, packet));
        }

        public static void processUseItemOnPacket(ServerPlayer player, ResourceKey<Level> dimension, byte[] packetBytes) {
            ServerLevel world = getServerWorld(player, dimension);
            if (world == null) return;

            ServerboundUseItemOnPacket packet = decodePacket(packetBytes, ServerboundUseItemOnPacket.STREAM_CODEC);
            withRedirect(new Context(world, packet.getHitResult()), () -> doProcessUseItemOn(world, player, packet));
        }

        private static ServerLevel getServerWorld(ServerPlayer player, ResourceKey<Level> dimension) {
            ServerLevel world = player.server.getLevel(dimension);
            Validate.notNull(world, "missing %s", dimension.location());
            return world;
        }

        private static <T> T decodePacket(byte[] packetBytes, Codec<T> codec) {
            FriendlyByteBuf buf = IPMcHelper.bytesToBuf(packetBytes);
            return codec.decode(buf);
        }
    }

    public static void init() {}

    private static void withRedirect(Context context, Runnable runnable) {
        Context original = REDIRECT_CONTEXT.get();
        REDIRECT_CONTEXT.set(context);
        try {
            PacketRedirection.withForceRedirect(context.world(), runnable);
        } finally {
            REDIRECT_CONTEXT.set(original);
        }
    }

    private static void doProcessPlayerAction(ServerLevel world, ServerPlayer player, ServerboundPlayerActionPacket packet) {
        player.resetLastActionTime();
        BlockPos blockPos = packet.getPos();
        ServerboundPlayerActionPacket.Action action = packet.getAction();

        if (!canPlayerReach(world.dimension(), player, blockPos)) {
            LOGGER.error("Reject cross-portal action {} {} {}", player, world, blockPos);
            return;
        }

        if (isAttackingAction(action)) {
            player.gameMode.handleBlockBreakAction(
                blockPos, action, packet.getDirection(),
                world.getMaxBuildHeight(), packet.getSequence()
            );
            player.connection.ackBlockChangesUpTo(packet.getSequence());
        }
    }

    public static boolean isAttackingAction(ServerboundPlayerActionPacket.Action action) {
        return action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK ||
            action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK ||
            action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK;
    }

    private static void doProcessUseItemOn(ServerLevel world, ServerPlayer player, ServerboundUseItemOnPacket packet) {
        player.connection.ackBlockChangesUpTo(packet.getSequence());
        InteractionHand hand = packet.getHand();
        BlockHitResult blockHitResult = packet.getHitResult();
        ResourceKey<Level> dimension = world.dimension();

        ItemStack itemStack = player.getItemInHand(hand);
        if (!itemStack.isItemEnabled(world.enabledFeatures())) return;

        BlockPos blockPos = blockHitResult.getBlockPos();
        Direction direction = blockHitResult.getDirection();
        player.resetLastActionTime();

        if (world.mayInteract(player, blockPos) && canPlayerReach(dimension, player, blockPos)) {
            InteractionResult actionResult = player.gameMode.useItemOn(player, world, itemStack, hand, blockHitResult);
            if (actionResult.shouldSwing()) {
                player.swing(hand, true);
            }
        }

        PacketRedirection.sendRedirectedMessage(player, dimension, new ClientboundBlockUpdatePacket(world, blockPos));
        BlockPos offsetPos = blockPos.relative(direction);

        if (offsetPos.getY() >= world.getMinBuildHeight() && offsetPos.getY() < world.getMaxBuildHeight()) {
            PacketRedirection.sendRedirectedMessage(player, dimension, new ClientboundBlockUpdatePacket(world, offsetPos));
        }
    }

    public static boolean validateReach(Player player, Level targetWorld, BlockPos targetPos) {
        PortalUtils.PortalAwareRaytraceResult result = PortalUtils.portalAwareRayTrace(
            player.level(),
            player.getEyePosition(),
            player.getViewVector(1),
            32,
            player,
            ClipContext.Block.COLLIDER
        );

        return result != null && result.world() == targetWorld &&
               result.hitResult().getBlockPos().distManhattan(targetPos) < 8;
    }
}