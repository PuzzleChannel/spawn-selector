package dev.para.spawnselector;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.storage.LevelData;

import java.util.Set;

public class SpawnMenuHandler {

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();

            if (!SpawnData.hasSelectedSpawn(player)) {
                server.execute(() -> {
                    player.openMenu(new SimpleMenuProvider(
                        (id, inventory, p) -> new SpawnSelectorMenu(id, inventory, p),
                        Component.literal("Выбор спавна")
                    ));
                });
            }
        });
    }

    public static void handleSpawnSelection(ServerPlayer player, int spawnIndex) {
        if (spawnIndex < 0 || spawnIndex >= SpawnConfig.spawnPoints.size()) {
            SpawnSelectorMod.LOGGER.warn("Некорректный индекс спавна: {}", spawnIndex);
            return;
        }

        SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(spawnIndex);
        ServerLevel level = player.level();

        level.getServer().getCommands().performPrefixedCommand(
            level.getServer().createCommandSourceStack().withSuppressedOutput(),
            "spreadplayers " + spawn.x + " " + spawn.z + " " +
            "1 " + SpawnConfig.radius + " false " + player.getGameProfile().name()
        );

        level.getServer().execute(() -> {
            player.setYRot(spawn.yaw);
            player.setXRot(0);
        });

        level.getServer().getCommands().performPrefixedCommand(
            level.getServer().createCommandSourceStack().withSuppressedOutput(),
            "spawnpoint " + player.getGameProfile().name() + " ~ ~ ~"
        );

        String command = "team join " + spawn.team + " " + player.getGameProfile().name();

        level.getServer().getCommands().performPrefixedCommand(
            level.getServer().createCommandSourceStack().withSuppressedOutput(),
            command
        );

        if (player.getTeam() == null) {
            SpawnSelectorMod.LOGGER.warn("Игрок {} НЕ добавлен в команду {}", player.getGameProfile().name(), spawn.team);
        }

        SpawnData.markSpawnSelected(player);
        player.closeContainer();
    }

    private static BlockPos findSafeSpawnPosition(ServerLevel level, BlockPos startPos) {
        if (!level.hasChunk(startPos.getX() >> 4, startPos.getZ() >> 4)) {
            return startPos;
        }

        for (int yOffset = 0; yOffset <= 15; yOffset++) {
            BlockPos checkPos = startPos.offset(0, yOffset, 0);

            if (checkPos.getY() < -64 || checkPos.getY() >= 318) {
                continue;
            }

            if (!level.hasChunk(checkPos.getX() >> 4, checkPos.getZ() >> 4)) {
                continue;
            }

            BlockPos feetPos = checkPos;
            BlockPos headPos = checkPos.above();
            BlockPos groundPos = checkPos.below();

            boolean feetClear = level.getBlockState(feetPos).isAir() ||
                               !level.getBlockState(feetPos).blocksMotion();
            boolean headClear = level.getBlockState(headPos).isAir() ||
                               !level.getBlockState(headPos).blocksMotion();
            boolean groundSolid = level.getBlockState(groundPos).isSolid() &&
                                 !level.getBlockState(groundPos).isAir();

            if (feetClear && headClear && groundSolid) {
                return feetPos;
            }
        }

        for (int yOffset = -1; yOffset >= -10; yOffset--) {
            BlockPos checkPos = startPos.offset(0, yOffset, 0);

            if (checkPos.getY() < -64) {
                break;
            }

            if (!level.hasChunk(checkPos.getX() >> 4, checkPos.getZ() >> 4)) {
                continue;
            }

            BlockPos feetPos = checkPos;
            BlockPos headPos = checkPos.above();
            BlockPos groundPos = checkPos.below();

            boolean feetClear = level.getBlockState(feetPos).isAir() ||
                               !level.getBlockState(feetPos).blocksMotion();
            boolean headClear = level.getBlockState(headPos).isAir() ||
                               !level.getBlockState(headPos).blocksMotion();
            boolean groundSolid = level.getBlockState(groundPos).isSolid() &&
                                 !level.getBlockState(groundPos).isAir();

            if (feetClear && headClear && groundSolid) {
                return feetPos;
            }
        }

        return startPos;
    }
}
