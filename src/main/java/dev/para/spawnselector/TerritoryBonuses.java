package dev.para.spawnselector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-authoritative passive bonuses for every chunk owned by a bound Territory Map side.
 *
 * Territory Map is the source of truth: administrator-defined coreAreas and player-owned
 * frontierAreas are both included. VMWar changes frontier ownership through the same API,
 * so the bonus follows a chunk automatically after a successful claim or war transfer.
 */
public final class TerritoryBonuses {
    private static final String TERRITORY_MAP_REVISION_KEY = "territorymap:war_api_v1:revision";
    private static final String TERRITORY_MAP_SNAPSHOT_KEY = "territorymap:war_api_v1:snapshot";
    private static final int TERRITORY_MAP_API_VERSION = 1;
    private static final int MAX_PACKET_PART_CHARS = 18_000;

    private static final float EUROPE_ORDINARY_MINING_MULTIPLIER = 1.20F;
    private static final float AFRICA_ORE_MINING_MULTIPLIER = 1.30F;
    private static final double AFRICA_EXTRA_DROP_CHANCE = 0.07D;
    private static final int EUROPE_EXTRA_TRADE_XP = 2;
    private static final int MOVEMENT_EFFECT_DURATION = 12;
    private static final String EMPTY_CLIENT_SNAPSHOT =
            "{\"api\":1,\"revision\":0,\"territories\":[]}";

    private static final Map<UUID, Integer> lastTradeCounts = new HashMap<>();
    private static final Set<UUID> areaSpeedEffects = new HashSet<>();
    private static final Set<UUID> areaSwimEffects = new HashSet<>();

    private static volatile TerritoryIndex serverTerritories = TerritoryIndex.empty();
    private static volatile TerritoryIndex clientTerritories = TerritoryIndex.empty();
    private static volatile String clientSnapshotJson = EMPTY_CLIENT_SNAPSHOT;

    private static LongSupplier revisionSupplier;
    private static Supplier<String> snapshotSupplier;
    private static long appliedRevision = Long.MIN_VALUE;
    private static MinecraftServer activeServer;
    private static boolean apiWarningLogged;

    private static long clientAssemblyRevision = Long.MIN_VALUE;
    private static long clientAppliedRevision = Long.MIN_VALUE;
    private static String[] clientAssemblyParts;

    private TerritoryBonuses() {}

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            lastTradeCounts.put(player.getUUID(), getVillagerTradeCount(player));
            server.execute(() -> {
                activeServer = server;
                if (refreshTerritoryMap(false)) {
                    syncAllPlayers(server);
                } else {
                    sendTerritorySnapshot(player);
                }
            });
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
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (activeServer == server) {
                activeServer = null;
                revisionSupplier = null;
                snapshotSupplier = null;
                appliedRevision = Long.MIN_VALUE;
                serverTerritories = TerritoryIndex.empty();
                lastTradeCounts.clear();
                areaSpeedEffects.clear();
                areaSwimEffects.clear();
            }
        });
    }

    /**
     * Returns true when the player's current chunk belongs to the requested side.
     * Server checks use the live server snapshot; client mining feedback uses the
     * latest compact snapshot sent by the server.
     */
    public static boolean isInsideTeamTerritory(Player player, String territoryTeam) {
        if (player == null || territoryTeam == null) return false;
        String playerTeam = normalizeSide(getTeamName(player));
        String requestedTeam = normalizeSide(territoryTeam);
        if (playerTeam == null || !playerTeam.equals(requestedTeam)) return false;

        String dimension = player.level().dimension().identifier().toString();
        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;
        TerritoryIndex index = player instanceof ServerPlayer ? serverTerritories : clientTerritories;
        return index.contains(playerTeam, dimension, chunkX, chunkZ);
    }

    /** Called by the shared BlockState mixin on both client and server for responsive mining. */
    public static float miningSpeedMultiplier(Player player, BlockState state) {
        if (isInsideTeamTerritory(player, "europe") && !isOre(state)) {
            return EUROPE_ORDINARY_MINING_MULTIPLIER;
        }
        if (isInsideTeamTerritory(player, "africa") && isOre(state)) {
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

    private static String getTeamName(Player player) {
        Team team = player.getTeam();
        return team == null ? "" : team.getName();
    }

    private static void tick(MinecraftServer server) {
        activeServer = server;
        if (refreshTerritoryMap(false)) {
            // A claim, admin edit, or completed war changes the Territory Map revision.
            // Refresh mining feedback for everyone without requiring a reconnect.
            syncAllPlayers(server);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();

            int currentTrades = getVillagerTradeCount(player);
            Integer oldTrades = lastTradeCounts.put(id, currentTrades);
            if (oldTrades == null) oldTrades = currentTrades;
            if (currentTrades > oldTrades && isInsideTeamTerritory(player, "europe")) {
                // Additional player XP per completed villager trade; vanilla trade XP remains unchanged.
                player.giveExperiencePoints((currentTrades - oldTrades) * EUROPE_EXTRA_TRADE_XP);
            }

            if (isInsideTeamTerritory(player, "avstralia")) {
                maintainAreaEffect(player, MobEffects.SPEED, areaSpeedEffects);
                maintainAreaEffect(player, MobEffects.DOLPHINS_GRACE, areaSwimEffects);
            } else {
                // The short effect expires naturally shortly after the player leaves owned land.
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

        // Refresh only effects introduced by this feature. Existing stronger potion effects are preserved.
        if (trackedPlayers.contains(id) && current.getAmplifier() == 0 && current.getDuration() <= 4) {
            player.addEffect(new MobEffectInstance(effect, MOVEMENT_EFFECT_DURATION, 0, true, false, false));
        }
    }

    private static void onBlockBroken(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)
                || !(player instanceof ServerPlayer serverPlayer)
                || !isOre(state)
                || !isInsideTeamTerritory(serverPlayer, "africa")) {
            return;
        }

        // Small chance to avoid flooding the resource economy.
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

    /**
     * Called from the server join handler. The payload contains only territory ownership
     * rectangles; map labels, colours and editor-only data are not sent to clients.
     */
    public static void syncToPlayer(ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        MinecraftServer server = player.level().getServer();
        activeServer = server;
        if (refreshTerritoryMap(false)) syncAllPlayers(server);
        else sendTerritorySnapshot(player);
    }

    /** Sends a fresh Territory Map snapshot to all online clients. */
    public static void syncAll(MinecraftServer server) {
        if (server == null) return;
        activeServer = server;
        refreshTerritoryMap(true);
        syncAllPlayers(server);
    }

    private static void syncAllPlayers(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) sendTerritorySnapshot(player);
    }

    private static void sendTerritorySnapshot(ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        String json = clientSnapshotJson;
        int totalParts = Math.max(1, (json.length() + MAX_PACKET_PART_CHARS - 1) / MAX_PACKET_PART_CHARS);
        for (int part = 0; part < totalParts; part++) {
            int start = part * MAX_PACKET_PART_CHARS;
            int end = Math.min(json.length(), start + MAX_PACKET_PART_CHARS);
            JsonObject packet = new JsonObject();
            packet.addProperty("revision", appliedRevision == Long.MIN_VALUE ? 0L : appliedRevision);
            packet.addProperty("part", part);
            packet.addProperty("parts", totalParts);
            packet.addProperty("data", json.substring(start, end));
            ServerPlayNetworking.send(player, new SpawnPayloads.TerritoryData(packet.toString()));
        }
    }

    /**
     * Connects to Territory Map war API v1 and refreshes only when its revision changes.
     * The API is intentionally discovered through Fabric ObjectShare, so Spawn Selector
     * remains loadable without a hard class dependency on Territory Map.
     *
     * @return true when a new, revision-consistent ownership snapshot was applied.
     */
    private static boolean refreshTerritoryMap(boolean force) {
        if (!connectToTerritoryMap()) {
            if (appliedRevision != -1L) {
                appliedRevision = -1L;
                serverTerritories = TerritoryIndex.empty();
                clientSnapshotJson = EMPTY_CLIENT_SNAPSHOT;
                return true;
            }
            return false;
        }

        final long currentRevision;
        try {
            currentRevision = revisionSupplier.getAsLong();
        } catch (RuntimeException exception) {
            SpawnSelectorMod.LOGGER.warn("Не удалось получить revision Territory Map.", exception);
            return false;
        }
        if (!force && currentRevision == appliedRevision) return false;

        try {
            String sourceJson = snapshotSupplier.get();
            JsonObject root = JsonParser.parseString(sourceJson).getAsJsonObject();
            if (!root.has("api") || root.get("api").getAsInt() != TERRITORY_MAP_API_VERSION) {
                throw new IllegalStateException("Unsupported Territory Map war API version");
            }
            long snapshotRevision = root.get("revision").getAsLong();
            if (snapshotRevision != currentRevision) {
                // A map edit raced the snapshot. Retry on the next server tick.
                return false;
            }

            TerritoryIndex parsed = parseTerritoryMapSnapshot(root);
            serverTerritories = parsed;
            appliedRevision = snapshotRevision;
            clientSnapshotJson = createClientSnapshot(snapshotRevision, parsed);
            return true;
        } catch (RuntimeException exception) {
            SpawnSelectorMod.LOGGER.warn("Не удалось обновить территории из Territory Map.", exception);
            return false;
        }
    }

    private static boolean connectToTerritoryMap() {
        if (revisionSupplier != null && snapshotSupplier != null) return true;
        if (!FabricLoader.getInstance().isModLoaded("territorymap")) {
            logTerritoryApiUnavailableOnce("Territory Map не установлен; пассивные территориальные бонусы отключены.");
            return false;
        }

        Object revision = FabricLoader.getInstance().getObjectShare().get(TERRITORY_MAP_REVISION_KEY);
        Object snapshot = FabricLoader.getInstance().getObjectShare().get(TERRITORY_MAP_SNAPSHOT_KEY);
        if (!(revision instanceof LongSupplier revisionFn) || !(snapshot instanceof Supplier<?> snapshotFn)) {
            logTerritoryApiUnavailableOnce("Territory Map war API v1 не найден; установите Territory Map со сборкой feature/vmwar-territory-api.");
            return false;
        }

        @SuppressWarnings("unchecked")
        Supplier<String> typedSnapshot = (Supplier<String>) snapshotFn;
        revisionSupplier = revisionFn;
        snapshotSupplier = typedSnapshot;
        return true;
    }

    private static void logTerritoryApiUnavailableOnce(String message) {
        if (apiWarningLogged) return;
        apiWarningLogged = true;
        SpawnSelectorMod.LOGGER.warn(message);
    }

    private static TerritoryIndex parseTerritoryMapSnapshot(JsonObject root) {
        Map<TeamDimension, List<ChunkArea>> result = new HashMap<>();
        JsonArray territories = root.getAsJsonArray("territories");
        if (territories == null) return TerritoryIndex.empty();

        for (JsonElement element : territories) {
            if (!element.isJsonObject()) continue;
            JsonObject territory = element.getAsJsonObject();
            String side = territory.has("warSide") && !territory.get("warSide").isJsonNull()
                    ? normalizeSide(territory.get("warSide").getAsString()) : null;
            String dimension = territory.has("dimension") ? territory.get("dimension").getAsString() : "";
            if (side == null || dimension.isBlank()) continue;

            List<ChunkArea> ownedAreas = new ArrayList<>();
            decodeAreas(territory.getAsJsonArray("coreAreas"), ownedAreas);
            decodeAreas(territory.getAsJsonArray("frontierAreas"), ownedAreas);
            // Compatibility fallback for an API snapshot that has not yet split core/frontier.
            if (ownedAreas.isEmpty()) decodeAreas(territory.getAsJsonArray("areas"), ownedAreas);
            if (!ownedAreas.isEmpty()) {
                result.computeIfAbsent(new TeamDimension(side, dimension), ignored -> new ArrayList<>())
                        .addAll(ownedAreas);
            }
        }

        return TerritoryIndex.immutable(result);
    }

    private static void decodeAreas(JsonArray array, List<ChunkArea> destination) {
        if (array == null) return;
        for (JsonElement element : array) {
            if (!element.isJsonArray()) continue;
            JsonArray rect = element.getAsJsonArray();
            if (rect.size() != 4) continue;
            int minX = rect.get(0).getAsInt();
            int minZ = rect.get(1).getAsInt();
            int maxX = rect.get(2).getAsInt();
            int maxZ = rect.get(3).getAsInt();
            if (minX <= maxX && minZ <= maxZ) {
                destination.add(new ChunkArea(minX, minZ, maxX, maxZ));
            }
        }
    }

    private static String createClientSnapshot(long revision, TerritoryIndex index) {
        JsonObject root = new JsonObject();
        root.addProperty("api", TERRITORY_MAP_API_VERSION);
        root.addProperty("revision", revision);
        JsonArray territories = new JsonArray();
        for (Map.Entry<TeamDimension, List<ChunkArea>> entry : index.areasByTeam().entrySet()) {
            JsonObject territory = new JsonObject();
            territory.addProperty("side", entry.getKey().side());
            territory.addProperty("dimension", entry.getKey().dimension());
            JsonArray areas = new JsonArray();
            for (ChunkArea area : entry.getValue()) {
                JsonArray rect = new JsonArray();
                rect.add(area.minX());
                rect.add(area.minZ());
                rect.add(area.maxX());
                rect.add(area.maxZ());
                areas.add(rect);
            }
            territory.add("areas", areas);
            territories.add(territory);
        }
        root.add("territories", territories);
        return root.toString();
    }

    /** Receives a part of the compact server ownership snapshot on the client thread. */
    public static void updateClientTerritories(String packetJson) {
        try {
            JsonObject packet = JsonParser.parseString(packetJson).getAsJsonObject();
            long revision = packet.get("revision").getAsLong();
            int part = packet.get("part").getAsInt();
            int parts = packet.get("parts").getAsInt();
            String data = packet.get("data").getAsString();
            if (parts < 1 || parts > 512 || part < 0 || part >= parts
                    || revision < clientAppliedRevision) return;

            if (clientAssemblyParts == null || clientAssemblyRevision != revision
                    || clientAssemblyParts.length != parts) {
                clientAssemblyRevision = revision;
                clientAssemblyParts = new String[parts];
            }
            clientAssemblyParts[part] = data;

            StringBuilder joined = new StringBuilder();
            for (String value : clientAssemblyParts) {
                if (value == null) return;
                joined.append(value);
            }

            JsonObject snapshot = JsonParser.parseString(joined.toString()).getAsJsonObject();
            if (!snapshot.has("api") || snapshot.get("api").getAsInt() != TERRITORY_MAP_API_VERSION
                    || snapshot.get("revision").getAsLong() != revision) {
                throw new IllegalArgumentException("Неверная ревизия синхронизации территорий.");
            }

            Map<TeamDimension, List<ChunkArea>> parsed = new HashMap<>();
            JsonArray territories = snapshot.getAsJsonArray("territories");
            if (territories != null) {
                for (JsonElement element : territories) {
                    JsonObject territory = element.getAsJsonObject();
                    String side = normalizeSide(territory.get("side").getAsString());
                    String dimension = territory.get("dimension").getAsString();
                    if (side == null || dimension.isBlank()) continue;
                    List<ChunkArea> areas = new ArrayList<>();
                    decodeAreas(territory.getAsJsonArray("areas"), areas);
                    if (!areas.isEmpty()) parsed.put(new TeamDimension(side, dimension), areas);
                }
            }
            clientTerritories = TerritoryIndex.immutable(parsed);
            clientAppliedRevision = revision;
            clientAssemblyRevision = Long.MIN_VALUE;
            clientAssemblyParts = null;
        } catch (RuntimeException exception) {
            SpawnSelectorMod.LOGGER.warn("Не удалось принять снимок территорий от сервера.", exception);
        }
    }

    public static void clearClientTerritories() {
        clientTerritories = TerritoryIndex.empty();
        clientAssemblyRevision = Long.MIN_VALUE;
        clientAppliedRevision = Long.MIN_VALUE;
        clientAssemblyParts = null;
    }

    private static String normalizeSide(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("asia")) value = "avstralia";
        return value.equals("europe") || value.equals("africa") || value.equals("avstralia") ? value : null;
    }

    private record TeamDimension(String side, String dimension) {}
    private record ChunkArea(int minX, int minZ, int maxX, int maxZ) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private record TerritoryIndex(Map<TeamDimension, List<ChunkArea>> areasByTeam) {
        static TerritoryIndex empty() {
            return new TerritoryIndex(Map.of());
        }
        static TerritoryIndex immutable(Map<TeamDimension, List<ChunkArea>> map) {
            Map<TeamDimension, List<ChunkArea>> copy = new HashMap<>();
            map.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            return new TerritoryIndex(Map.copyOf(copy));
        }
        boolean contains(String side, String dimension, int chunkX, int chunkZ) {
            List<ChunkArea> areas = areasByTeam.get(new TeamDimension(side, dimension));
            if (areas == null) return false;
            for (ChunkArea area : areas) {
                if (area.contains(chunkX, chunkZ)) return true;
            }
            return false;
        }
    }
}
