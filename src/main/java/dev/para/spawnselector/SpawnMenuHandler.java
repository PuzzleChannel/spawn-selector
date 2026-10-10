package dev.para.spawnselector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class SpawnMenuHandler {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Set<UUID> OPEN_MENUS = new HashSet<>();
    private static final int FREE_SPAWN_MIN = -10_000;
    private static final int FREE_SPAWN_MAX = 10_000;
    private static final int FREE_SPAWN_ATTEMPTS = 512;
    private static final int LOCAL_SPAWN_RADIUS = 16;
    private static final int LOCAL_SPAWN_ATTEMPTS = 128;

    private SpawnMenuHandler() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (!SpawnData.hasSelectedSpawn(player)) server.execute(() -> openMenu(player));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                OPEN_MENUS.remove(handler.getPlayer().getUUID()));
    }

    public static void openMenu(ServerPlayer player) {
        if (player.isRemoved() || SpawnData.hasSelectedSpawn(player)) return;
        OPEN_MENUS.add(player.getUUID());
        sendMenu(player, "");
    }

    private static void sendMenu(ServerPlayer player, String message) {
        JsonObject root = new JsonObject();
        JsonArray teams = new JsonArray();
        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        Scoreboard scoreboard = server.getScoreboard();
        for (int i = 0; i < Math.min(3, SpawnConfig.spawnPoints.size()); i++) {
            SpawnConfig.SpawnPoint point = SpawnConfig.spawnPoints.get(i);
            JsonObject team = new JsonObject();
            team.addProperty("name", point.name);
            team.addProperty("description", point.description == null ? "" : point.description);
            team.addProperty("image", point.image == null ? "" : point.image);
            PlayerTeam scoreboardTeam = scoreboard.getPlayerTeam(point.team);
            team.addProperty("count", scoreboardTeam == null ? 0 : scoreboardTeam.getPlayers().size());
            team.addProperty("max", SpawnConfig.maxPlayersPerTeam);
            team.addProperty("color", point.color == null ? "" : point.color);
            teams.add(team);
        }
        root.add("teams", teams);
        root.addProperty("message", message == null ? "" : message);
        ServerPlayNetworking.send(player, new SpawnPayloads.OpenMenu(GSON.toJson(root)));
    }

    private static void reject(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
        sendMenu(player, message);
    }

    public static void handleSpawnSelection(ServerPlayer player, int spawnIndex) {
        if (player == null || player.isRemoved() || SpawnData.hasSelectedSpawn(player)) return;
        if (!OPEN_MENUS.contains(player.getUUID())) return;
        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        if (server == null) return;
        // Spawn selection always targets the Overworld, regardless of the dimension
        // in which a returning player happened to join.
        ServerLevel level = server.overworld();

        // Fourth choice is independent survival: it receives a safe random land spawn but no team.
        if (spawnIndex == 3) {
            BlockPos safe = findRandomLandSpawn(player, level);
            if (safe == null) {
                reject(player, "Не удалось найти безопасную сушу в радиусе ±10 000 блоков. Попробуйте ещё раз.");
                return;
            }
            if (!teleportAndSetRespawn(player, level, safe, 0.0f)) {
                reject(player, "Не удалось установить точку появления. Попробуйте ещё раз.");
                return;
            }
            OPEN_MENUS.remove(player.getUUID());
            SpawnData.markSpawnSelected(player);
            player.sendSystemMessage(Component.literal("Вы выбрали свободное выживание. Начальная точка: "
                    + safe.getX() + " " + safe.getY() + " " + safe.getZ()));
            ServerPlayNetworking.send(player, new SpawnPayloads.CloseMenu());
            return;
        }

        if (spawnIndex < 0 || spawnIndex >= 3 || spawnIndex >= SpawnConfig.spawnPoints.size()) {
            reject(player, "Этот вариант выбора недоступен.");
            return;
        }

        SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(spawnIndex);
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam targetTeam = scoreboard.getPlayerTeam(spawn.team);
        if (targetTeam == null) {
            reject(player, "Эта команда временно недоступна. Сообщите администрации.");
            SpawnSelectorMod.LOGGER.error("Не найдена scoreboard-команда '{}' для точки '{}'.", spawn.team, spawn.name);
            return;
        }

        int targetCount = targetTeam.getPlayers().size();
        int smallestCount = Integer.MAX_VALUE;
        for (SpawnConfig.SpawnPoint point : SpawnConfig.spawnPoints) {
            PlayerTeam team = scoreboard.getPlayerTeam(point.team);
            int count = team == null ? 0 : team.getPlayers().size();
            smallestCount = Math.min(smallestCount, count);
        }
        if (smallestCount == Integer.MAX_VALUE) smallestCount = 0;

        if (SpawnConfig.maxPlayersPerTeam > 0 && targetCount >= SpawnConfig.maxPlayersPerTeam) {
            reject(player, "Команда «" + spawn.name + "» уже заполнена (" +
                    targetCount + "/" + SpawnConfig.maxPlayersPerTeam + "). Выберите другую команду.");
            return;
        }
        if (targetCount + 1 - smallestCount > SpawnConfig.maxTeamImbalance) {
            reject(player, "В команде «" + spawn.name +
                    "» сейчас слишком много игроков. Выберите менее заполненную команду.");
            return;
        }

        if (spawn.y < level.getMinY() || spawn.y >= level.getMaxY()) {
            reject(player, "Точка появления настроена некорректно. Сообщите администрации.");
            SpawnSelectorMod.LOGGER.error("Y={} вне границ мира для точки '{}'.", spawn.y, spawn.name);
            return;
        }

        BlockPos safe = findLandNear(level, (int) Math.floor(spawn.x), (int) Math.floor(spawn.z),
                Math.max(0, Math.min(128, SpawnConfig.radius)), 48);
        if (safe == null) {
            reject(player, "Рядом с точкой «" + spawn.name + "» не найдена безопасная суша. Сообщите администрации.");
            return;
        }

        boolean added = scoreboard.addPlayerToTeam(player.getScoreboardName(), targetTeam);
        if (!added && scoreboard.getPlayersTeam(player.getScoreboardName()) != targetTeam) {
            reject(player, "Не удалось назначить команду. Попробуйте ещё раз или обратитесь к администрации.");
            return;
        }
        if (!teleportAndSetRespawn(player, level, safe, spawn.yaw)) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), targetTeam);
            reject(player, "Не удалось установить точку появления. Попробуйте ещё раз.");
            return;
        }

        OPEN_MENUS.remove(player.getUUID());
        SpawnData.markSpawnSelected(player);
        player.sendSystemMessage(Component.literal("Вы выбрали команду «" + spawn.name + "». Добро пожаловать!"));
        ServerPlayNetworking.send(player, new SpawnPayloads.CloseMenu());
    }

    private static BlockPos findRandomLandSpawn(ServerPlayer player, ServerLevel level) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Prefer a genuinely random, distant location so players do not all spawn
        // at the same place just because they are standing together when selecting.
        for (int i = 0; i < FREE_SPAWN_ATTEMPTS; i++) {
            int x = random.nextInt(FREE_SPAWN_MIN, FREE_SPAWN_MAX + 1);
            int z = random.nextInt(FREE_SPAWN_MIN, FREE_SPAWN_MAX + 1);
            BlockPos candidate = findLandNear(level, x, z, 0, 1);
            if (candidate != null) {
                SpawnSelectorMod.LOGGER.info("Для свободного спавна игрока {} выбрана случайная безопасная суша: {}.",
                        player.getGameProfile().name(), candidate);
                return candidate;
            }
        }

        // Only fall back to the player's surroundings if the wider search failed.
        if (player.level() instanceof ServerLevel playerLevel
                && playerLevel.dimension().equals(level.dimension())) {
            BlockPos current = player.blockPosition();
            BlockPos nearby = findLandNear(level, current.getX(), current.getZ(),
                    LOCAL_SPAWN_RADIUS, LOCAL_SPAWN_ATTEMPTS);
            if (nearby != null) {
                SpawnSelectorMod.LOGGER.info("Глобальный поиск суши не удался; для игрока {} найдена безопасная позиция рядом с текущей: {}.",
                        player.getGameProfile().name(), nearby);
                return nearby;
            }

            // Last resort: use the exact current block only if it is safe and not foliage.
            if (isSafeSpawnPosition(level, current)) {
                SpawnSelectorMod.LOGGER.warn("Для игрока {} использована текущая позиция как последний резервный вариант: {}.",
                        player.getGameProfile().name(), current);
                return current;
            }
        }

        SpawnSelectorMod.LOGGER.warn("Не найдена безопасная суша для свободного спавна игрока {}. Текущая позиция: {} {} {}.",
                player.getGameProfile().name(), player.getX(), player.getY(), player.getZ());
        return null;
    }

    private static BlockPos findLandNear(ServerLevel level, int centerX, int centerZ, int radius, int attempts) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < attempts; i++) {
            int x = centerX;
            int z = centerZ;
            if (radius > 0) {
                x += random.nextInt(-radius, radius + 1);
                z += random.nextInt(-radius, radius + 1);
            }
            if (Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984) continue;
            int y;
            try {
                y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            } catch (RuntimeException e) {
                SpawnSelectorMod.LOGGER.debug("Не удалось получить высоту для спавна {},{}.", x, z, e);
                continue;
            }
            if (y <= level.getMinY() || y + 1 >= level.getMaxY()) continue;
            BlockPos feet = new BlockPos(x, y, z);
            if (isSafeSpawnPosition(level, feet)) return feet;
        }
        return null;
    }

    private static boolean isSafeSpawnPosition(ServerLevel level, BlockPos feet) {
        if (feet.getY() <= level.getMinY() || feet.getY() + 1 >= level.getMaxY()) return false;

        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        var floorState = level.getBlockState(floor);
        var feetState = level.getBlockState(feet);
        var headState = level.getBlockState(head);

        // Never allow foliage in the support block or in the player's body space.
        return !(floorState.getBlock() instanceof LeavesBlock)
                && !(feetState.getBlock() instanceof LeavesBlock)
                && !(headState.getBlock() instanceof LeavesBlock)
                && floorState.blocksMotion() && !floorState.liquid()
                && !feetState.blocksMotion() && !feetState.liquid()
                && !headState.blocksMotion() && !headState.liquid()
                && !isHazard(floorState.getBlock())
                && !isHazard(feetState.getBlock())
                && !isHazard(headState.getBlock());
    }

    private static boolean isHazard(net.minecraft.world.level.block.Block block) {
        return block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.CACTUS
                || block == Blocks.MAGMA_BLOCK || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.POWDER_SNOW;
    }

    private static boolean teleportAndSetRespawn(ServerPlayer player, ServerLevel level, BlockPos pos, float yaw) {
        MinecraftServer server = level.getServer();
        String name = player.getGameProfile().name();
        String teleport = "tp " + name + " " + (pos.getX() + 0.5) + " " + pos.getY() + " "
                + (pos.getZ() + 0.5) + " " + yaw + " 0";
        try {
            int tpResult = server.getCommands().getDispatcher().execute(
                    teleport, server.createCommandSourceStack().withSuppressedOutput());
            if (tpResult == 0) return false;
            int spawnResult = server.getCommands().getDispatcher().execute(
                    "spawnpoint " + name + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ(),
                    server.createCommandSourceStack().withSuppressedOutput());
            return spawnResult != 0;
        } catch (CommandSyntaxException e) {
            SpawnSelectorMod.LOGGER.error("Не удалось выполнить команды телепортации/установки спавна для игрока {}.", name, e);
            return false;
        }
    }
}
