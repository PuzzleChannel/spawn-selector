package dev.para.spawnselector;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpawnSelectorMod implements ModInitializer {
    public static final String MOD_ID = "spawnselector";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Инициализация Spawn Selector...");
        SpawnConfig.load();
        SpawnData.load();
        SpawnMenuHandler.register();
        SpawnCommand.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> server.execute(() -> ensureTeamsExist(server)));
        LOGGER.info("Spawn Selector инициализирован.");
    }

    private void ensureTeamsExist(net.minecraft.server.MinecraftServer server) {
        String[] defaultColors = {"green", "blue", "gold"};
        for (int i = 0; i < SpawnConfig.spawnPoints.size(); i++) {
            SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(i);
            var scoreboard = server.getScoreboard();
            var team = scoreboard.getPlayerTeam(spawn.team);
            if (team == null) {
                team = scoreboard.addPlayerTeam(spawn.team);
                LOGGER.info("Создана команда: {}", spawn.team);
            }
            String color = spawn.color != null && !spawn.color.isBlank()
                    ? spawn.color : defaultColors[Math.min(i, defaultColors.length - 1)];
            try {
                team.setColor(net.minecraft.ChatFormatting.valueOf(color.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Неизвестный цвет '{}' для команды '{}'; использую цвет по умолчанию.", color, spawn.team);
                team.setColor(net.minecraft.ChatFormatting.valueOf(defaultColors[i].toUpperCase(java.util.Locale.ROOT)));
            }
        }
    }
}
