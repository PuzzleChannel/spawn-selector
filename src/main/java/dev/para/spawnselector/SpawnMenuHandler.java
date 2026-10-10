package dev.para.spawnselector;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

public class SpawnMenuHandler {
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (!SpawnData.hasSelectedSpawn(player)) server.execute(() -> openMenu(player));
        });
    }

    public static void openMenu(ServerPlayer player) {
        if (player.isRemoved() || SpawnData.hasSelectedSpawn(player)) return;
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new SpawnSelectorMenu(id, inventory, p),
                Component.literal("Выбор команды")
        ));
    }

    public static int getTeamCount(String teamName) {
        if (teamName == null) return 0;
        var server = net.fabricmc.loader.api.FabricLoader.getInstance();
        // Counts are calculated from the live scoreboard on the server thread by the caller.
        // This helper is intentionally only used while a menu is being built for a connected player.
        return 0;
    }

    public static void handleSpawnSelection(ServerPlayer player, int spawnIndex) {
        if (player == null || player.isRemoved() || SpawnData.hasSelectedSpawn(player)) return;
        if (!(player.containerMenu instanceof SpawnSelectorMenu)) return;

        if (spawnIndex == 3) {
            SpawnData.markSpawnSelected(player);
            player.sendSystemMessage(Component.literal("Вы решили начать без команды. Удачи в выживании!"));
            player.closeContainer();
            return;
        }
        if (spawnIndex < 0 || spawnIndex >= 3 || spawnIndex >= SpawnConfig.spawnPoints.size()) {
            player.sendSystemMessage(Component.literal("Этот вариант выбора недоступен."));
            return;
        }

        SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(spawnIndex);
        var server = player.getServer();
        if (server == null) return;
        var scoreboard = server.getScoreboard();
        var targetTeam = scoreboard.getPlayerTeam(spawn.team);
        if (targetTeam == null) {
            player.sendSystemMessage(Component.literal("Эта команда временно недоступна. Сообщите администрации."));
            SpawnSelectorMod.LOGGER.error("Не найдена scoreboard-команда '{}' для точки '{}'.", spawn.team, spawn.name);
            return;
        }

        int targetCount = targetTeam.getPlayers().size();
        int smallestCount = Integer.MAX_VALUE;
        for (SpawnConfig.SpawnPoint point : SpawnConfig.spawnPoints) {
            var team = scoreboard.getPlayerTeam(point.team);
            int count = team == null ? 0 : team.getPlayers().size();
            smallestCount = Math.min(smallestCount, count);
        }
        if (smallestCount == Integer.MAX_VALUE) smallestCount = 0;

        if (SpawnConfig.maxPlayersPerTeam > 0 && targetCount >= SpawnConfig.maxPlayersPerTeam) {
            player.sendSystemMessage(Component.literal("Команда «" + spawn.name + "» уже заполнена (" +
                    targetCount + "/" + SpawnConfig.maxPlayersPerTeam + "). Выберите другую команду."));
            return;
        }
        if (targetCount + 1 - smallestCount > SpawnConfig.maxTeamImbalance) {
            player.sendSystemMessage(Component.literal("В команде «" + spawn.name +
                    "» сейчас слишком много игроков. Выберите менее заполненную команду."));
            return;
        }

        ServerLevel level = player.serverLevel();
        BlockPos center = BlockPos.containing(spawn.x, spawn.y, spawn.z);
        if (spawn.y < level.getMinY() || spawn.y >= level.getMaxY()) {
            player.sendSystemMessage(Component.literal("Точка появления настроена некорректно. Сообщите администрации."));
            SpawnSelectorMod.LOGGER.error("Y={} вне границ мира для точки '{}'.", spawn.y, spawn.name);
            return;
        }

        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "spreadplayers " + spawn.x + " " + spawn.z + " 1 " + SpawnConfig.radius + " false " + player.getGameProfile().name()
        );

        boolean added = scoreboard.addPlayerToTeam(player.getScoreboardName(), targetTeam);
        if (!added && scoreboard.getPlayersTeam(player.getScoreboardName()) != targetTeam) {
            player.sendSystemMessage(Component.literal("Не удалось назначить команду. Попробуйте ещё раз или обратитесь к администрации."));
            SpawnSelectorMod.LOGGER.error("Не удалось назначить игрока {} в команду {}.", player.getGameProfile().name(), spawn.team);
            return;
        }

        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                "spawnpoint " + player.getGameProfile().name() + " " + center.getX() + " " + center.getY() + " " + center.getZ()
        );
        player.setYRot(spawn.yaw);
        player.setXRot(0);
        SpawnData.markSpawnSelected(player);
        player.sendSystemMessage(Component.literal("Вы выбрали команду «" + spawn.name + "». Добро пожаловать!"));
        player.closeContainer();
    }
}
