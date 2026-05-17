package com.niconator.tramplegrass;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

@Mod(TrampleGrass.MODID)
public class TrampleGrass {
    public static final String MODID = "trample_grass";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<ServerLevel, LevelTrampleData> LEVEL_DATA = new WeakHashMap<>();
    private static final double FEET_OFFSET = 0.2D;
    private static final double FOOTPRINT_INSET = 1.0E-5D;

    public TrampleGrass(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(Config::onConfigLoad);
        modEventBus.addListener(Config::onConfigReload);
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        Level level = entity.level();
        if (!(level instanceof ServerLevel serverLevel) || !entity.onGround() || entity.isRemoved()) {
            clearEntityFootprint(serverLevelOrNull(level), entity.getUUID());
            return;
        }

        Set<BlockPos> currentGrassBlocks = grassBlocksUnder(entity, serverLevel);
        if (currentGrassBlocks.isEmpty()) {
            clearEntityFootprint(serverLevel, entity.getUUID());
            return;
        }

        LevelTrampleData data = dataFor(serverLevel);
        UUID entityId = entity.getUUID();
        Set<BlockPos> previousGrassBlocks = data.entityFootprints.getOrDefault(entityId, Set.of());
        for (BlockPos previousPos : previousGrassBlocks) {
            if (!currentGrassBlocks.contains(previousPos)) {
                removeOccupant(data, previousPos, entityId);
            }
        }

        long gameTime = serverLevel.getGameTime();
        for (BlockPos pos : currentGrassBlocks) {
            handleGrassStep(serverLevel, data, entity, pos.immutable(), gameTime, !previousGrassBlocks.contains(pos));
        }

        data.entityFootprints.put(entityId, currentGrassBlocks);
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        LevelTrampleData data = dataFor(serverLevel);
        long gameTime = serverLevel.getGameTime();
        tickWatchedGrass(serverLevel, data, gameTime);
        tickPathRegrowth(serverLevel, data);
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            dataFor(serverLevel).loadedChunks.add(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            LevelTrampleData data = LEVEL_DATA.get(serverLevel);
            if (data != null) {
                data.loadedChunks.remove(event.getChunk().getPos().toLong());
            }
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            LEVEL_DATA.remove(serverLevel);
        }
    }

    private static void tickWatchedGrass(ServerLevel level, LevelTrampleData data, long gameTime) {
        Iterator<Map.Entry<BlockPos, WatchedGrassBlock>> iterator = data.watchedBlocks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, WatchedGrassBlock> entry = iterator.next();
            if (entry.getValue().expiresAtTick < gameTime || !level.getBlockState(entry.getKey()).is(Blocks.GRASS_BLOCK)) {
                iterator.remove();
            }
        }
    }

    private static void tickPathRegrowth(ServerLevel level, LevelTrampleData data) {
        if (data.loadedChunks.isEmpty()) {
            return;
        }

        int randomTickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        if (randomTickSpeed <= 0) {
            return;
        }

        Set<Long> loadedChunks = new HashSet<>(data.loadedChunks);
        for (long chunkPosLong : loadedChunks) {
            if (!level.getChunkSource().hasChunk(ChunkPos.getX(chunkPosLong), ChunkPos.getZ(chunkPosLong))) {
                data.loadedChunks.remove(chunkPosLong);
                continue;
            }

            if (!level.shouldTickBlocksAt(chunkPosLong)) {
                continue;
            }

            tickPathRegrowthInChunk(level, chunkPosLong, randomTickSpeed);
        }
    }

    private static void tickPathRegrowthInChunk(ServerLevel level, long chunkPosLong, int randomTickSpeed) {
        ChunkPos chunkPos = new ChunkPos(chunkPosLong);
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        int minSection = level.getMinSection();
        int maxSection = level.getMaxSection();
        for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
            int baseY = sectionY << 4;
            for (int attempt = 0; attempt < randomTickSpeed; attempt++) {
                mutablePos.set(
                        chunkPos.getBlockX(level.random.nextInt(16)),
                        baseY + level.random.nextInt(16),
                        chunkPos.getBlockZ(level.random.nextInt(16))
                );
                tickPathLikeGrass(level, mutablePos);
            }
        }
    }

    private static void tickPathLikeGrass(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(Blocks.DIRT_PATH)) {
            return;
        }

        BlockState grassState = Blocks.GRASS_BLOCK.defaultBlockState();
        if (!canGrassSurvive(grassState, level, pos)) {
            level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
            debug("Turned dirt path into dirt because grass cannot survive at " + format(pos));
            return;
        }

        if (
                level.getMaxLocalRawBrightness(pos.above()) >= 9
                        && (Config.pathRegrowthSlowdown <= 1 || level.random.nextInt(Config.pathRegrowthSlowdown) == 0)
                        && canPropagateGrass(grassState, level, pos)
        ) {
            level.setBlockAndUpdate(pos, grassState);
            debug("Regrew dirt path into grass at " + format(pos));
        }
    }

    private static void handleGrassStep(
            ServerLevel level,
            LevelTrampleData data,
            Entity entity,
            BlockPos pos,
            long gameTime,
            boolean enteredBlock
    ) {
        WatchedGrassBlock watchedBlock = data.watchedBlocks.get(pos);
        if (watchedBlock == null || watchedBlock.expiresAtTick < gameTime) {
            watchedBlock = new WatchedGrassBlock();
            data.watchedBlocks.put(pos, watchedBlock);
            watchedBlock.occupants.add(entity.getUUID());
            refresh(watchedBlock, gameTime);
            debug("Started watching grass at " + format(pos) + " after " + entity.getName().getString() + " stepped on it");
            return;
        }

        if (enteredBlock && watchedBlock.occupants.add(entity.getUUID())) {
            rollForPath(level, pos, entity);
        }

        refresh(watchedBlock, gameTime);
    }

    private static void rollForPath(ServerLevel level, BlockPos pos, Entity entity) {
        double chance = Config.pathChancePercent;
        if (chance <= 0.0D) {
            debug("Trample roll skipped at " + format(pos) + " because chance is 0%");
            return;
        }

        if (chance >= 100.0D || level.random.nextDouble() * 100.0D < chance) {
            level.setBlockAndUpdate(pos, Blocks.DIRT_PATH.defaultBlockState());
            debug(entity.getName().getString() + " trampled grass into a dirt path at " + format(pos));
            return;
        }

        debug(entity.getName().getString() + " failed to trample grass at " + format(pos) + "; watch duration refreshed");
    }

    private static Set<BlockPos> grassBlocksUnder(Entity entity, ServerLevel level) {
        AABB box = entity.getBoundingBox();
        int minX = Mth.floor(box.minX + FOOTPRINT_INSET);
        int maxX = Mth.floor(box.maxX - FOOTPRINT_INSET);
        int minZ = Mth.floor(box.minZ + FOOTPRINT_INSET);
        int maxZ = Mth.floor(box.maxZ - FOOTPRINT_INSET);
        int y = Mth.floor(entity.getY() - FEET_OFFSET);

        Set<BlockPos> positions = new HashSet<>();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                mutablePos.set(x, y, z);
                if (level.getBlockState(mutablePos).is(Blocks.GRASS_BLOCK)) {
                    positions.add(mutablePos.immutable());
                }
            }
        }
        return positions;
    }

    private static boolean canPropagateGrass(BlockState state, ServerLevel level, BlockPos pos) {
        return canGrassSurvive(state, level, pos) && !level.getFluidState(pos.above()).is(FluidTags.WATER);
    }

    private static boolean canGrassSurvive(BlockState state, ServerLevel level, BlockPos pos) {
        BlockPos abovePos = pos.above();
        BlockState aboveState = level.getBlockState(abovePos);
        if (aboveState.is(Blocks.SNOW) && aboveState.getValue(SnowLayerBlock.LAYERS) == 1) {
            return true;
        }

        if (aboveState.getFluidState().getAmount() == 8) {
            return false;
        }

        int lightBlock = LightEngine.getLightBlockInto(
                level,
                state,
                pos,
                aboveState,
                abovePos,
                Direction.UP,
                aboveState.getLightBlock(level, abovePos)
        );
        return lightBlock < level.getMaxLightLevel();
    }

    private static void clearEntityFootprint(ServerLevel level, UUID entityId) {
        if (level == null) {
            return;
        }

        LevelTrampleData data = LEVEL_DATA.get(level);
        if (data == null) {
            return;
        }

        Set<BlockPos> previousGrassBlocks = data.entityFootprints.remove(entityId);
        if (previousGrassBlocks == null) {
            return;
        }

        for (BlockPos previousPos : previousGrassBlocks) {
            removeOccupant(data, previousPos, entityId);
        }
    }

    private static void removeOccupant(LevelTrampleData data, BlockPos pos, UUID entityId) {
        WatchedGrassBlock watchedBlock = data.watchedBlocks.get(pos);
        if (watchedBlock != null) {
            watchedBlock.occupants.remove(entityId);
        }
    }

    private static void refresh(WatchedGrassBlock watchedBlock, long gameTime) {
        watchedBlock.expiresAtTick = gameTime + Config.watchDurationTicks;
    }

    private static LevelTrampleData dataFor(ServerLevel level) {
        return LEVEL_DATA.computeIfAbsent(level, ignored -> new LevelTrampleData());
    }

    private static ServerLevel serverLevelOrNull(Level level) {
        return level instanceof ServerLevel serverLevel ? serverLevel : null;
    }

    private static String format(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static void debug(String message) {
        if (Config.debug) {
            LOGGER.debug(MODID + ": " + message);
        }
    }

    private static final class LevelTrampleData {
        private final Map<BlockPos, WatchedGrassBlock> watchedBlocks = new HashMap<>();
        private final Map<UUID, Set<BlockPos>> entityFootprints = new HashMap<>();
        private final Set<Long> loadedChunks = new HashSet<>();
    }

    private static final class WatchedGrassBlock {
        private long expiresAtTick;
        private final Set<UUID> occupants = new HashSet<>();
    }
}
