package dev.para.spawnselector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
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
    // Players waiting for EasyAuth must be able to use its login/registration UI.
    // Their selector is opened only after EasyAuth reports successful authentication.
    private static final Set<UUID> PENDING_AUTH_MENUS = new HashSet<>();
    private static boolean authBridgeWarningLogged;
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
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            OPEN_MENUS.remove(id);
            PENDING_AUTH_MENUS.remove(id);
        });
    }

    public static void openMenu(ServerPlayer player) {
        if (player == null || player.isRemoved() || SpawnData.hasSelectedSpawn(player)) {
            if (player != null) {
                PENDING_AUTH_MENUS.remove(player.getUUID());
                OPEN_MENUS.remove(player.getUUID());
            }
            return;
        }
        UUID playerId = player.getUUID();
        if (!isAuthenticationSatisfied(player)) {
            // Do not send OpenMenu at JOIN: EasyAuth owns the UI until login/register.
            OPEN_MENUS.remove(playerId);
            PENDING_AUTH_MENUS.add(playerId);
            return;
        }
        PENDING_AUTH_MENUS.remove(playerId);
        OPEN_MENUS.add(playerId);
        sendMenu(player, "");
    }

    public static void tickOpenMenus(MinecraftServer server) {
        // Poll pending players independently from the menu-refresh timer. EasyAuth marks
        // a player authenticated as soon as login/registration (or auto-login) succeeds.
        for (UUID id : Set.copyOf(PENDING_AUTH_MENUS)) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.isRemoved() || SpawnData.hasSelectedSpawn(player)) {
                PENDING_AUTH_MENUS.remove(id);
                continue;
            }
            if (isAuthenticationSatisfied(player)) {
                PENDING_AUTH_MENUS.remove(id);
                openMenu(player);
            }
        }

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
        int smallestCount = Integer.MAX_VALUE;
        for (SpawnConfig.SpawnPoint point : SpawnConfig.spawnPoints) {
            PlayerTeam team = scoreboard.getPlayerTeam(point.team);
            smallestCount = Math.min(smallestCount, team == null ? 0 : team.getPlayers().size());
        }
        if (smallestCount == Integer.MAX_VALUE) smallestCount = 0;
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
            int count = scoreboardTeam == null ? 0 : scoreboardTeam.getPlayers().size();
            team.addProperty("available", scoreboardTeam != null
                    && (SpawnConfig.maxPlayersPerTeam <= 0 || count < SpawnConfig.maxPlayersPerTeam)
                    && count + 1 - smallestCount <= SpawnConfig.maxTeamImbalance);
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
        // Defense in depth: never accept a selection packet before EasyAuth authentication.
        if (!isAuthenticationSatisfied(player)) {
            OPEN_MENUS.remove(player.getUUID());
            PENDING_AUTH_MENUS.add(player.getUUID());
            return;
        }
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
            // Free survival must reset any territory LuckPerms parent (e.g. africa)
            // back to the default group instead of leaving the previous group assigned.
            assignLuckPermsGroup(player, "default");
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

        // Assign the matching LuckPerms group and replace the player's previous parent groups.
        // Free survival resets the parent to "default" in its dedicated branch above.
        assignLuckPermsGroup(player, spawn.team);

        OPEN_MENUS.remove(player.getUUID());
        SpawnData.markSpawnSelected(player);
        ServerPlayNetworking.send(player, new SpawnPayloads.CloseMenu());
    }

    /**
     * Checks EasyAuth's own authenticated flag without a hard dependency on its classes.
     * This keeps Spawn Selector usable without EasyAuth while integrating safely when present.
     * If EasyAuth is installed but its bridge cannot be read, fail closed instead of allowing
     * the selector to interfere with registration or accept a pre-authentication packet.
     */
    private static boolean isAuthenticationSatisfied(ServerPlayer player) {
        if (!FabricLoader.getInstance().isModLoaded("easyauth")) return true;
        try {
            java.lang.reflect.Method method = player.getClass().getMethod("easyAuth$isAuthenticated");
            return Boolean.TRUE.equals(method.invoke(player));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!authBridgeWarningLogged) {
                authBridgeWarningLogged = true;
                SpawnSelectorMod.LOGGER.error(
                        "EasyAuth обнаружен, но не удалось проверить состояние авторизации. " +
                        "Выбор территории будет ждать корректной авторизации; проверьте совместимость EasyAuth.", e);
            }
            return false;
        }
    }

    private static void assignLuckPermsGroup(ServerPlayer player, String group) {
        if (player == null || group == null
                || !group.matches("[a-zA-Z0-9_-]{1,32}")
                || !(group.equals("europe") || group.equals("africa")
                || group.equals("avstralia") || group.equals("default"))) {
            SpawnSelectorMod.LOGGER.error("Отказ от назначения некорректной группы LuckPerms: '{}'", group);
            return;
        }

        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        if (server == null) return;

        String command = "lp user " + player.getGameProfile().name() + " parent set " + group;
        try {
            int result = server.getCommands().getDispatcher().execute(
                    command, server.createCommandSourceStack().withSuppressedOutput());
            if (result == 0) {
                SpawnSelectorMod.LOGGER.warn("LuckPerms не подтвердил назначение группы '{}' игроку {}.",
                        group, player.getGameProfile().name());
            } else {
                SpawnSelectorMod.LOGGER.info("Игроку {} назначена группа LuckPerms '{}'; прежние родительские группы заменены.",
                        player.getGameProfile().name(), group);
            }
        } catch (CommandSyntaxException e) {
            // Do not block spawn selection if LuckPerms is absent or its command is unavailable.
            SpawnSelectorMod.LOGGER.warn("Не удалось выполнить команду LuckPerms для игрока {}. Проверьте, что LuckPerms установлен и доступен на сервере.",
                    player.getGameProfile().name(), e);
        }
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
