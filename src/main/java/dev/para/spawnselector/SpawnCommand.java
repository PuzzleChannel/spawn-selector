package dev.para.spawnselector;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class SpawnCommand {
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("spp")
                .requires(source -> !source.isPlayer() || source.getServer().getPlayerList().isOp(source.getPlayer().nameAndId()))
                .then(Commands.literal("set")
                    .then(Commands.argument("index", IntegerArgumentType.integer(1, 3))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> {
                            int index = IntegerArgumentType.getInteger(context, "index") - 1;
                            BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            if (index >= SpawnConfig.spawnPoints.size()) {
                                context.getSource().sendFailure(Component.literal("Точка не существует. Используйте /spp list."));
                                return 0;
                            }
                            SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(index);
                            spawn.x = pos.getX(); spawn.y = pos.getY(); spawn.z = pos.getZ(); spawn.yaw = player.getYRot();
                            SpawnConfig.save();
                            context.getSource().sendSuccess(() -> Component.literal("Точка «" + spawn.name + "» установлена: " +
                                    pos.getX() + " " + pos.getY() + " " + pos.getZ()), true);
                            return 1;
                        }))))
                .then(Commands.literal("list").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("=== Точки появления ==="), false);
                    for (int i = 0; i < SpawnConfig.spawnPoints.size(); i++) {
                        SpawnConfig.SpawnPoint p = SpawnConfig.spawnPoints.get(i);
                        int index = i + 1;
                        context.getSource().sendSuccess(() -> Component.literal(index + ". " + p.name + " — " +
                                (int)p.x + " " + (int)p.y + " " + (int)p.z + " (команда: " + p.team + ")"), false);
                    }
                    context.getSource().sendSuccess(() -> Component.literal("4. Свободное выживание — случайная суша в пределах ±10 000 блоков"), false);
                    return 1;
                }))
                .then(Commands.literal("teams").executes(context -> {
                    var scoreboard = context.getSource().getServer().getScoreboard();
                    context.getSource().sendSuccess(() -> Component.literal("=== Состав команд ==="), false);
                    for (SpawnConfig.SpawnPoint point : SpawnConfig.spawnPoints) {
                        var team = scoreboard.getPlayerTeam(point.team);
                        int count = team == null ? 0 : team.getPlayers().size();
                        context.getSource().sendSuccess(() -> Component.literal(point.name + ": " + count +
                                (SpawnConfig.maxPlayersPerTeam > 0 ? "/" + SpawnConfig.maxPlayersPerTeam : "") +
                                " игроков (команда " + point.team + ")"), false);
                    }
                    context.getSource().sendSuccess(() -> Component.literal("Допустимый перевес: " + SpawnConfig.maxTeamImbalance), false);
                    return 1;
                }))
                .then(Commands.literal("setname")
                    .then(Commands.argument("index", IntegerArgumentType.integer(1, 3))
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(context -> {
                            int index = IntegerArgumentType.getInteger(context, "index") - 1;
                            String name = StringArgumentType.getString(context, "name").trim();
                            if (index >= SpawnConfig.spawnPoints.size() || name.isEmpty() || name.length() > 48) {
                                context.getSource().sendFailure(Component.literal("Укажите существующую точку и название длиной 1–48 символов."));
                                return 0;
                            }
                            SpawnConfig.spawnPoints.get(index).name = name;
                            SpawnConfig.save();
                            context.getSource().sendSuccess(() -> Component.literal("Название точки " + (index + 1) + " изменено: " + name), true);
                            return 1;
                        }))))
                .then(Commands.literal("reset")
                    .then(Commands.argument("target", EntityArgument.player()).executes(context -> {
                        ServerPlayer target = EntityArgument.getPlayer(context, "target");
                        SpawnData.resetSpawnSelection(target);
                        var scoreboard = target.getServer().getScoreboard();
                        var oldTeam = scoreboard.getPlayersTeam(target.getScoreboardName());
                        if (oldTeam != null) scoreboard.removePlayerFromTeam(target.getScoreboardName(), oldTeam);
                        context.getSource().sendSuccess(() -> Component.literal("Выбор сброшен для " + target.getGameProfile().name()), true);
                        target.closeContainer();
                        target.server.execute(() -> SpawnMenuHandler.openMenu(target));
                        return 1;
                    })))
            )
        );
    }
}