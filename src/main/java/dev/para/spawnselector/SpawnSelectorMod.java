package dev.para.spawnselector;

import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpawnSelectorMod implements ModInitializer {
    public static final String MOD_ID = "spawnselector";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override public void onInitialize() {
        LOGGER.info("Инициализация Spawn Selector...");
        PayloadTypeRegistry.clientboundPlay().register(SpawnPayloads.OpenMenu.TYPE, SpawnPayloads.OpenMenu.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SpawnPayloads.CloseMenu.TYPE, SpawnPayloads.CloseMenu.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SpawnPayloads.TeamsUpdate.TYPE, SpawnPayloads.TeamsUpdate.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SpawnPayloads.Choose.TYPE, SpawnPayloads.Choose.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SpawnPayloads.Choose.TYPE, (payload, context) ->
                ((net.minecraft.server.level.ServerLevel) context.player().level()).getServer().execute(() ->
                        SpawnMenuHandler.handleSpawnSelection(context.player(), payload.index())));
        SpawnConfig.load();
        SpawnData.load();
        SpawnMenuHandler.register();
        SpawnCommand.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> server.execute(() -> ensureTeamsExist(server)));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(SpawnMenuHandler::tickOpenMenus);
        LOGGER.info("Spawn Selector инициализирован.");
    }

    private void ensureTeamsExist(net.minecraft.server.MinecraftServer server) {
        String[] defaultColors = {"green", "blue", "gold"};
        for (int i = 0; i < SpawnConfig.spawnPoints.size(); i++) {
            SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(i);
            var scoreboard = server.getScoreboard();
            var team = scoreboard.getPlayerTeam(spawn.team);
            if (team == null) team = scoreboard.addPlayerTeam(spawn.team);
            String[] oldTeamIds = {"team1", "team2", "team3"};
            if (i < oldTeamIds.length) {
                var oldTeam = scoreboard.getPlayerTeam(oldTeamIds[i]);
                if (oldTeam != null && oldTeam != team) {
                    for (String member : java.util.Set.copyOf(oldTeam.getPlayers())) {
                        scoreboard.addPlayerToTeam(member, team);
                    }
                }
            }
            String color = spawn.color != null && !spawn.color.isBlank()
                    ? spawn.color : defaultColors[Math.min(i, defaultColors.length - 1)];
            try {
                team.setColor(java.util.Optional.of(net.minecraft.world.scores.TeamColor.valueOf(color.toUpperCase(java.util.Locale.ROOT))));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Неизвестный цвет '{}' для команды '{}'; использую цвет по умолчанию.", color, spawn.team);
                team.setColor(java.util.Optional.of(net.minecraft.world.scores.TeamColor.valueOf(defaultColors[Math.min(i, defaultColors.length - 1)].toUpperCase(java.util.Locale.ROOT))));
            }
        }
    }
}