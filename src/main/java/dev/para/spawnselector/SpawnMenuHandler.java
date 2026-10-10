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
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class SpawnMenuHandler {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Set<UUID> OPEN_MENUS = new HashSet<>();
    private static final int FREE_SPAWN_CENTER_X = -16_000;
    private static final int FREE_SPAWN_CENTER_Z = -6_000;
    private static final int FREE_SPAWN_SPREAD = 2_000;

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

        // Free survival uses a dedicated 4000x4000 area centered at X=-16000, Z=-6000.
        if (spawnIndex == 3) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int x = random.nextInt(FREE_SPAWN_CENTER_X - FREE_SPAWN_SPREAD,
                    FREE_SPAWN_CENTER_X + FREE_SPAWN_SPREAD + 1);
            int z = random.nextInt(FREE_SPAWN_CENTER_Z - FREE_SPAWN_SPREAD,
                    FREE_SPAWN_CENTER_Z + FREE_SPAWN_SPREAD + 1);
            int y;
            try {
                y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            } catch (RuntimeException e) {
                SpawnSelectorMod.LOGGER.error("Не удалось определить высоту для свободного спавна в {},{}.", x, z, e);
                reject(player, "Не удалось определить высоту в зоне свободного выживания. Попробуйте ещё раз.");
                return;
            }
            if (y <= level.getMinY() || y >= level.getMaxY()) {
                int fallbackY = level.getSeaLevel() + 1;
                y = Math.max(level.getMinY() + 1, Math.min(level.getMaxY() - 2, fallbackY));
                SpawnSelectorMod.LOGGER.warn(
                        "Heightmap вернул недопустимую высоту для свободного спавна X={}, Z={} (Y={}); использую резервную Y={}.",
                        x, z, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), y);
            }
            BlockPos destination = new BlockPos(x, y, z);
            if (!teleportAndSetRespawn(player, level, destination, 0.0f)) {
                reject(player, "Не удалось установить точку появления. Попробуйте ещё раз.");
                return;
            }
            OPEN_MENUS.remove(player.getUUID());
            SpawnData.markSpawnSelected(player);
            player.sendSystemMessage(Component.literal("Вы выбрали свободное выживание. Зона бомжей: "
                    + destination.getX() + " " + destination.getY() + " " + destination.getZ()));
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

        // Team choices teleport directly to configured coordinates; no land-safety search.
        BlockPos destination = BlockPos.containing(spawn.x, spawn.y, spawn.z);

        boolean added = scoreboard.addPlayerToTeam(player.getScoreboardName(), targetTeam);
        if (!added && scoreboard.getPlayersTeam(player.getScoreboardName()) != targetTeam) {
            reject(player, "Не удалось назначить команду. Попробуйте ещё раз или обратитесь к администрации.");
            return;
        }
        if (!teleportAndSetRespawn(player, level, destination, spawn.yaw)) {
            scoreboard.removePlayerFromTeam(player.getScoreboardName(), targetTeam);
            reject(player, "Не удалось установить точку появления. Попробуйте ещё раз.");
            return;
        }

        OPEN_MENUS.remove(player.getUUID());
        SpawnData.markSpawnSelected(player);
        player.sendSystemMessage(Component.literal("Вы выбрали команду «" + spawn.name + "». Добро пожаловать!"));
        ServerPlayNetworking.send(player, new SpawnPayloads.CloseMenu());
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
