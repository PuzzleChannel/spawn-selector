package dev.para.spawnselector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-authoritative bonuses centered on each configured territory spawn point.
 * The client receives only the capital coordinates needed to mirror mining-speed feedback.
 */
public final class TerritoryBonuses {
    public static final int BONUS_RADIUS = 300;

    private static final double RADIUS_SQUARED = (double) BONUS_RADIUS * BONUS_RADIUS;
    private static final float EUROPE_ORDINARY_MINING_MULTIPLIER = 1.20F;
    private static final float AFRICA_ORE_MINING_MULTIPLIER = 1.30F;
    private static final double AFRICA_EXTRA_DROP_CHANCE = 0.07D;
    private static final int EUROPE_EXTRA_TRADE_XP = 2;
    private static final int MOVEMENT_EFFECT_DURATION = 12;

    private static final Gson GSON = new GsonBuilder().create();
    private static volatile Map<String, Capital> clientCapitals = Map.of();

    private static final Map<UUID, Integer> lastTradeCounts = new HashMap<>();
    private static final Set<UUID> areaSpeedEffects = new HashSet<>();
    private static final Set<UUID> areaSwimEffects = new HashSet<>();

    private TerritoryBonuses() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            lastTradeCounts.put(player.getUUID(), getVillagerTradeCount(player));
            server.execute(() -> syncToPlayer(player));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            lastTradeCounts.remove(id);
            areaSpeedEffects.remove(id);
            areaSwimEffects.remove(id);
        });

        ServerTickEvents.END_SERVER_TICK.register(TerritoryBonuses::tick);
        PlayerBlockBreakEvents.AFTER.register(TerritoryBonuses::onBlockBroken);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            UUID id = oldPlayer.getUUID();
            areaSpeedEffects.remove(id);
            areaSwimEffects.remove(id);
            lastTradeCounts.put(newPlayer.getUUID(), getVillagerTradeCount(newPlayer));
        });
    }

    /** True only while the player is in the Overworld, on the specified scoreboard team and within 300 blocks. */
    public static boolean isInsideCapitalArea(Player player, String territoryTeam) {
        if (player == null || territoryTeam == null || player.level().dimension() != Level.OVERWORLD) return false;
        if (!territoryTeam.equals(getTeamName(player))) return false;

        Capital capital = getCapital(player, territoryTeam);
        if (capital == null) return false;

        double dx = player.getX() - capital.x();
        double dz = player.getZ() - capital.z();
        return dx * dx + dz * dz <= RADIUS_SQUARED;
    }

    /** Called by the shared BlockState mixin on both client and server for responsive mining. */
    public static float miningSpeedMultiplier(Player player, BlockState state) {
        if (isInsideCapitalArea(player, "europe") && !isOre(state)) {
            return EUROPE_ORDINARY_MINING_MULTIPLIER;
        }
        if (isInsideCapitalArea(player, "africa") && isOre(state)) {
            return AFRICA_ORE_MINING_MULTIPLIER;
        }
        return 1.0F;
    }

    public static boolean isOre(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)
                || state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)
                || state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                || state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)
                || state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)
                || state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)
                || state.is(Blocks.LAPIS_ORE) || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
                || state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
                || state.is(Blocks.NETHER_QUARTZ_ORE)
                || state.is(Blocks.NETHER_GOLD_ORE)
                || state.is(Blocks.ANCIENT_DEBRIS);
    }

    private static Capital getCapital(Player player, String team) {
        if (player instanceof ServerPlayer) {
            for (SpawnConfig.SpawnPoint point : SpawnConfig.spawnPoints) {
                if (team.equals(point.team)) return new Capital(point.x, point.z);
            }
            return null;
        }
        return clientCapitals.get(team);
    }

    private static String getTeamName(Player player) {
        Team team = player.getTeam();
        return team == null ? "" : team.getName();
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();

            int currentTrades = getVillagerTradeCount(player);
            Integer oldTrades = lastTradeCounts.put(id, currentTrades);
            if (oldTrades == null) {
                oldTrades = currentTrades;
            }
            if (currentTrades > oldTrades && isInsideCapitalArea(player, "europe")) {
                // This is additional player XP per completed villager trade; vanilla trade XP remains unchanged.
                player.giveExperiencePoints((currentTrades - oldTrades) * EUROPE_EXTRA_TRADE_XP);
            }

            if (isInsideCapitalArea(player, "avstralia")) {
                maintainAreaEffect(player, MobEffects.SPEED, areaSpeedEffects);
                maintainAreaEffect(player, MobEffects.DOLPHINS_GRACE, areaSwimEffects);
            } else {
                // Area effects have a short duration, so they fade naturally shortly after leaving the zone.
                areaSpeedEffects.remove(id);
                areaSwimEffects.remove(id);
            }
        }
    }

    private static int getVillagerTradeCount(ServerPlayer player) {
        try {
            return player.getStats().getValue(Stats.CUSTOM.get(Stats.TRADED_WITH_VILLAGER));
        } catch (RuntimeException exception) {
            SpawnSelectorMod.LOGGER.debug("Не удалось прочитать статистику торговли игрока {}.",
                    player.getGameProfile().name(), exception);
            return 0;
        }
    }

    private static void maintainAreaEffect(ServerPlayer player, Holder<MobEffect> effect, Set<UUID> trackedPlayers) {
        UUID id = player.getUUID();
        MobEffectInstance current = player.getEffect(effect);
        if (current == null) {
            if (player.addEffect(new MobEffectInstance(effect, MOVEMENT_EFFECT_DURATION, 0, true, false, false))) {
                trackedPlayers.add(id);
            }
            return;
        }

        // Refresh only effects that this feature introduced. Existing stronger/longer potion effects are preserved.
        if (trackedPlayers.contains(id) && current.getAmplifier() == 0 && current.getDuration() <= 4) {
            player.addEffect(new MobEffectInstance(effect, MOVEMENT_EFFECT_DURATION, 0, true, false, false));
        }
    }

    private static void onBlockBroken(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)
                || !(player instanceof ServerPlayer serverPlayer)
                || !isOre(state)
                || !isInsideCapitalArea(serverPlayer, "africa")) {
            return;
        }

        // A deliberately small chance, so the resource bonus stays useful without flooding the economy.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() >= AFRICA_EXTRA_DROP_CHANCE) return;

        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, blockEntity,
                serverPlayer, serverPlayer.getMainHandItem());
        ItemStack resource = drops.stream()
                .filter(stack -> !stack.isEmpty() && !stack.is(state.getBlock().asItem()))
                .findFirst()
                .orElse(ItemStack.EMPTY);
        if (resource.isEmpty()) return; // Includes Silk Touch drops.

        ItemStack bonus = resource.copy();
        bonus.setCount(random.nextBoolean() ? 1 : 2);
        Block.popResource(serverLevel, pos, bonus);
    }

    public static void syncToPlayer(ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        ServerPlayNetworking.send(player, new SpawnPayloads.TerritoryData(createCapitalJson()));
    }

    public static void syncAll(MinecraftServer server) {
        if (server == null) return;
        String json = createCapitalJson();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, new SpawnPayloads.TerritoryData(json));
        }
    }

    private static String createCapitalJson() {
        JsonObject root = new JsonObject();
        root.addProperty("radius", BONUS_RADIUS);
        JsonArray points = new JsonArray();
        for (SpawnConfig.SpawnPoint point : SpawnConfig.spawnPoints) {
            JsonObject entry = new JsonObject();
            entry.addProperty("team", point.team);
            entry.addProperty("x", point.x);
            entry.addProperty("z", point.z);
            points.add(entry);
        }
        root.add("points", points);
        return GSON.toJson(root);
    }

    /** Called only by the client packet receiver; no client config file is trusted for coordinates. */
    public static void updateClientCapitals(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray points = root.getAsJsonArray("points");
            Map<String, Capital> parsed = new HashMap<>();
            if (points != null) {
                for (int i = 0; i < points.size(); i++) {
                    JsonObject entry = points.get(i).getAsJsonObject();
                    String team = entry.get("team").getAsString();
                    double x = entry.get("x").getAsDouble();
                    double z = entry.get("z").getAsDouble();
                    if (team.matches("[a-zA-Z0-9_.-]{1,16}") && Double.isFinite(x) && Double.isFinite(z)) {
                        parsed.put(team, new Capital(x, z));
                    }
                }
            }
            clientCapitals = Map.copyOf(parsed);
        } catch (RuntimeException exception) {
            SpawnSelectorMod.LOGGER.warn("Не удалось прочитать координаты столиц с сервера.", exception);
        }
    }

    public static void clearClientCapitals() {
        clientCapitals = Map.of();
    }

    private record Capital(double x, double z) {}
}
