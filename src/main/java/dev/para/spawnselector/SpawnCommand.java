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

import java.util.Objects;

public class SpawnCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                Commands.literal("spp")
                    .requires(source -> !source.isPlayer() ||
                            source.getServer().getPlayerList().isOp(Objects.requireNonNull(source.getPlayer()).nameAndId()))
                    .then(Commands.literal("set")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1, 5))
                            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> {
                                    int index = IntegerArgumentType.getInteger(context, "index") - 1;
                                    BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");

                                    ServerPlayer player = context.getSource().getPlayerOrException();

                                    if (index >= SpawnConfig.spawnPoints.size()) {
                                        context.getSource().sendFailure(
                                            Component.literal("Точка спавна " + (index + 1) + " не существует. Используйте /spp list"));
                                        return 0;
                                    }

                                    SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(index);
                                    spawn.x = pos.getX();
                                    spawn.y = pos.getY();
                                    spawn.z = pos.getZ();
                                    spawn.yaw = player.getYRot();

                                    SpawnConfig.save();

                                    context.getSource().sendSuccess(() ->
                                        Component.literal("Точка спавна " + (index + 1) + " (" + spawn.name + ") установлена на "
                                            + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                                            + " для команды " + spawn.team), true);

                                    return 1;
                                })
                            )
                        )
                    )
                    .then(Commands.literal("list")
                        .executes(context -> {
                            context.getSource().sendSuccess(() ->
                                Component.literal("=== Точки спавна ==="), false);

                            for (int i = 0; i < SpawnConfig.spawnPoints.size(); i++) {
                                SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(i);
                                final int index = i;
                                context.getSource().sendSuccess(() ->
                                    Component.literal((index + 1) + ". " + spawn.name + " - "
                                        + (int)spawn.x + " " + (int)spawn.y + " " + (int)spawn.z
                                        + " (команда: " + spawn.team + ")"), false);
                            }

                            return 1;
                        })
                    )
                    .then(Commands.literal("setname")
                        .then(Commands.argument("index", IntegerArgumentType.integer(1, 5))
                            .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> {
                                    int index = IntegerArgumentType.getInteger(context, "index") - 1;
                                    String name = StringArgumentType.getString(context, "name");

                                    if (index >= SpawnConfig.spawnPoints.size()) {
                                        context.getSource().sendFailure(
                                            Component.literal("Точка спавна " + (index + 1) + " не существует"));
                                        return 0;
                                    }

                                    SpawnConfig.spawnPoints.get(index).name = name;
                                    SpawnConfig.save();

                                    context.getSource().sendSuccess(() ->
                                        Component.literal("Название точки " + (index + 1) + " изменено на: " + name), true);

                                    return 1;
                                })
                            )
                        )
                    )
                    .then(Commands.literal("reset")
                        .then(Commands.argument("target", EntityArgument.player())
                            .executes(context -> {
                                ServerPlayer target = EntityArgument.getPlayer(context, "target");

                                SpawnData.resetSpawnSelection(target);

                                context.getSource().sendSuccess(() ->
                                    Component.literal("Выбор спавна сброшен для игрока " + target.getGameProfile().name()), true);

                                target.closeContainer();

                                target.level().getServer().execute(() -> {
                                    target.openMenu(new net.minecraft.world.SimpleMenuProvider(
                                        (id, inventory, p) -> new SpawnSelectorMenu(id, inventory, p),
                                        Component.literal("Выбор спавна")
                                    ));
                                });

                                return 1;
                            })
                        )
                    )
            );
        });
    }
}
