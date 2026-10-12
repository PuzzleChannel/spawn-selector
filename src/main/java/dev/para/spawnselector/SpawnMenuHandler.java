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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.PlayerTeam;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class SpawnMenuHandler {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Set<UUID> OPEN_MENUS = new HashSet<>();
    private static final int FREE_SPAWN_CENTER_X = 0;
    private static final int FREE_SPAWN_CENTER_Z = 0;
    private static final int FREE_SPAWN_SPREAD = 4_000;
    private static long lastMenuRefreshTick = -40;

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

    public static void tickOpenMenus(MinecraftServer server) {
        long tick = server.getTickCount();
        if (tick - lastMenuRefreshTick < 40) return;
        lastMenuRefreshTick = tick;
        for (UUID id : Set.copyOf(OPEN_MENUS)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.isRemoved() || SpawnData.hasSelectedSpawn(player)) {
                OPEN_MENUS.remove(id);
                continue;
            }
            sendTeamsUpdate(player);
        }
    }

    private static void sendTeamsUpdate(ServerPlayer player) {
        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        Scoreboard scoreboard = server.getScoreboard();
        int smallestCount = Integer.MAX_VALUE;
        for (SpawnConfig.SpawnPoint point : SpawnConfig.spawnPoints) {
            PlayerTeam team = scoreboard.getPlayerTeam(point.team);
            smallestCount = Math.min(smallestCount, team == null ? 0 : team.getPlayers().size());
        }
        if (smallestCount == Integer.MAX_VALUE) smallestCount = 0;
        JsonArray teams = new JsonArray();
        for (int i = 0; i < Math.min(3, SpawnConfig.spawnPoints.size()); i++) {
            SpawnConfig.SpawnPoint point = SpawnConfig.spawnPoints.get(i);
            PlayerTeam team = scoreboard.getPlayerTeam(point.team);
            int count = team == null ? 0 : team.getPlayers().size();
            boolean available = team != null
                    && (SpawnConfig.maxPlayersPerTeam <= 0 || count < SpawnConfig.maxPlayersPerTeam)
                    && count + 1 - smallestCount <= SpawnConfig.maxTeamImbalance;
            JsonObject item = new JsonObject();
            item.addProperty("count", count);
            item.addProperty("max", SpawnConfig.maxPlayersPerTeam);
            item.addProperty("available", available);
            teams.add(item);
        }
        ServerPlayNetworking.send(player, new SpawnPayloads.TeamsUpdate(GSON.toJson(teams)));
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
            team.addProperty("available", scoreboardTeam != null
                    && (SpawnConfig.maxPlayersPerTeam <= 0 || scoreboardTeam.getPlayers().size() < SpawnConfig.maxPlayersPerTeam));
            teams.add(team);
        }
        root.add("teams", teams);
        root.add("menuText", GSON.toJsonTree(SpawnConfig.menuText));
        root.addProperty("message", message == null ? "" : message);
        ServerPlayNetworking.send(player, new SpawnPayloads.OpenMenu(GSON.toJson(root)));
    }

    private static void reject(ServerPlayer player, String message) {
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

        // Free survival searches for a genuinely safe surface spot inside its dedicated region.
        if (spawnIndex == 3) {
            BlockPos destination = findSafeFreeSpawn(level);
            if (destination == null) {
                reject(player, SpawnConfig.menuText.errorFreeSpawn);
                return;
            }
            if (!teleportAndSetRespawn(player, level, destination, 0.0f)) {
                reject(player, SpawnConfig.menuText.errorTeleport);
                return;
            }
            OPEN_MENUS.remove(player.getUUID());
            SpawnData.markSpawnSelected(player);
            ServerPlayNetworking.send(player, new SpawnPayloads.CloseMenu());
            return;
        }

        if (spawnIndex < 0 || spawnIndex >= 3 || spawnIndex >= SpawnConfig.spawnPoints.size()) {
            reject(player, SpawnConfig.menuText.errorInvalidChoice);
            return;
        }

        SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(spawnIndex);
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam targetTeam = scoreboard.getPlayerTeam(spawn.team);
        if (targetTeam == null) {
            reject(player, SpawnConfig.menuText.errorTeamUnavailable);
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
            reject(player, format(SpawnConfig.menuText.errorTeamFull, spawn.name, targetCount, SpawnConfig.maxPlayersPerTeam));
            return;
        }
        if (targetCount + 1 - smallestCount > SpawnConfig.maxTeamImbalance) {
            reject(player, format(SpawnConfig.menuText.errorTeamImbalanced, spawn.name, targetCount, SpawnConfig.maxPlayersPerTeam));
            return;
        }

        if (spawn.y < level.getMinY() || spawn.y >= level.getMaxY()) {
            reject(player, SpawnConfig.menuText.errorInvalidY);
            SpawnSelectorMod.LOGGER.error("Y={} вне границ мира для точки '{}'.", spawn.y, spawn.name);
            return;
        }

        // Treat configured X/Z as the preferred area, but always resolve the actual
        // destination to a dry surface block with open sky and non-negative Y.
        BlockPos destination = findSafeSurfaceNear(level, spawn.x, spawn.z, SpawnConfig.radius);
        if (destination == null) {
            reject(player, SpawnConfig.menuText.errorNoSurface);
            SpawnSelectorMod.LOGGER.warn("Не найдена безопасная поверхность для территории '{}' около X={}, Z={}.",
                    spawn.name, spawn.x, spawn.z);
            return;
        }

        boolean added = scoreboard.addPlayerToTeam(player.getScoreboardName(), targetTeam);
        if (!added && scoreboard.getPlayersTeam(player.getScoreboardName()) != targetTeam) {
            reject(player, SpawnConfig.menuText.errorAssignTeam);
            return;
        }
        if (!teleportAndSetRespawn(player, level, destination, spawn.yaw)) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), targetTeam);
            reject(player, SpawnConfig.menuText.errorSpawnpoint);
            return;
        }

        OPEN_MENUS.remove(player.getUUID());
        SpawnData.markSpawnSelected(player);
        ServerPlayNetworking.send(player, new SpawnPayloads.CloseMenu());
    }

    private static String format(String template, String name, int count, int max) {
        return (template == null ? "" : template).replace("{name}", name == null ? "" : name)
                .replace("{count}", Integer.toString(count)).replace("{max}", Integer.toString(max));
    }

    private static BlockPos findSafeSurfaceNear(ServerLevel level, double centerX, double centerZ, int radius) {
        int baseX = (int) Math.floor(centerX);
        int baseZ = (int) Math.floor(centerZ);
        int attempts = Math.max(1, Math.min(64, (radius * 2 + 1) * (radius * 2 + 1)));
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Try the configured column first, then nearby columns within the configured radius.
        for (int attempt = 0; attempt < attempts; attempt++) {
            int x;
            int z;
            if (attempt == 0) {
                x = baseX;
                z = baseZ;
            } else {
                x = baseX + random.nextInt(-radius, radius + 1);
                z = baseZ + random.nextInt(-radius, radius + 1);
            }
            BlockPos safe = findSafeSurfaceInColumn(level, x, z);
            if (safe != null) return safe;
        }
        return null;
    }

    private static BlockPos findSafeSurfaceInColumn(ServerLevel level, int x, int z) {
        int minY = Math.max(0, level.getMinY() + 1);
        int maxY = level.getMaxY() - 2;
        try {
            level.getChunk(x >> 4, z >> 4);
            for (int y = maxY; y >= minY; y--) {
                BlockPos feet = new BlockPos(x, y, z);
                BlockPos head = feet.above();
                BlockPos floor = feet.below();
                var feetState = level.getBlockState(feet);
                var headState = level.getBlockState(head);
                var floorState = level.getBlockState(floor);

                if (!feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()) continue;
                if (!feetState.getCollisionShape(level, feet).isEmpty()
                        || !headState.getCollisionShape(level, head).isEmpty()) continue;
                if (!floorState.getFluidState().isEmpty()
                        || floorState.getCollisionShape(level, floor).isEmpty()) continue;
                if (!level.canSeeSky(feet)) continue;

                return feet;
            }
        } catch (RuntimeException e) {
            SpawnSelectorMod.LOGGER.warn("Ошибка проверки поверхности X={}, Z={}.", x, z, e);
        }
        return null;
    }

    private static BlockPos findSafeFreeSpawn(ServerLevel level) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int minY = level.getMinY() + 1;
        int maxY = level.getMaxY() - 2;

        // Do not trust heightmap values: custom Earth world generators may return
        // invalid heights. Generate/load each candidate chunk, then inspect real blocks.
        for (int attempt = 0; attempt < 32; attempt++) {
            int x = random.nextInt(FREE_SPAWN_CENTER_X - FREE_SPAWN_SPREAD,
                    FREE_SPAWN_CENTER_X + FREE_SPAWN_SPREAD + 1);
            int z = random.nextInt(FREE_SPAWN_CENTER_Z - FREE_SPAWN_SPREAD,
                    FREE_SPAWN_CENTER_Z + FREE_SPAWN_SPREAD + 1);
            try {
                level.getChunk(x >> 4, z >> 4);
                for (int y = maxY; y >= minY; y--) {
                    BlockPos feet = new BlockPos(x, y, z);
                    BlockPos head = feet.above();
                    BlockPos floor = feet.below();
                    var feetState = level.getBlockState(feet);
                    var headState = level.getBlockState(head);
                    var floorState = level.getBlockState(floor);

                    if (y < 0) continue;
                    if (!feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()) continue;
                    if (!feetState.getCollisionShape(level, feet).isEmpty()
                            || !headState.getCollisionShape(level, head).isEmpty()) continue;
                    if (!floorState.getFluidState().isEmpty()
                            || floorState.getCollisionShape(level, floor).isEmpty()) continue;
                    // Reject caves, overhangs and underground chambers: the selected position
                    // must be at non-negative altitude with an unobstructed view of the sky.
                    if (!level.canSeeSky(feet)) continue;

                    // Found a clear, dry surface position with open sky directly above it.
                    SpawnSelectorMod.LOGGER.info("Безопасный свободный спавн найден: X={}, Y={}, Z={} (попытка {}).",
                            x, y, z, attempt + 1);
                    return feet;
                }
            } catch (RuntimeException e) {
                SpawnSelectorMod.LOGGER.warn("Ошибка проверки свободного спавна X={}, Z={} (попытка {}).",
                        x, z, attempt + 1, e);
            }
        }
        SpawnSelectorMod.LOGGER.warn("Не найден безопасный свободный спавн после 32 попыток в области X={}±{}, Z={}±{}.",
                FREE_SPAWN_CENTER_X, FREE_SPAWN_SPREAD, FREE_SPAWN_CENTER_Z, FREE_SPAWN_SPREAD);
        return null;
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
