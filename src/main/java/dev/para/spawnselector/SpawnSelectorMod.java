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
        LOGGER.info("Initializing Spawn Selector Mod...");

        SpawnConfig.load();
        SpawnData.load();
        SpawnMenuHandler.register();
        SpawnCommand.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            server.execute(() -> {
                ensureTeamsExist(server);
            });
        });

        LOGGER.info("Spawn Selector Mod initialized!");
    }

    private void ensureTeamsExist(net.minecraft.server.MinecraftServer server) {
        String[] defaultColors = {"red", "blue", "green", "yellow", "light_purple"};

        for (int i = 0; i < SpawnConfig.spawnPoints.size(); i++) {
            SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(i);
            var scoreboard = server.getScoreboard();
            var team = scoreboard.getPlayerTeam(spawn.team);

            if (team == null) {
                server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(),
                    "team add " + spawn.team
                );
                LOGGER.info("Создана команда: {}", spawn.team);

                String color = (spawn.color != null && !spawn.color.isEmpty()) ? spawn.color :
                               (i < defaultColors.length ? defaultColors[i] : "white");

                server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(),
                    "team modify " + spawn.team + " color " + color
                );
                LOGGER.info("Установлен цвет {} для команды {}", color, spawn.team);
            } else {
                LOGGER.info("Команда {} уже существует", spawn.team);
            }
        }
    }
}
