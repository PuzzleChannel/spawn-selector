package dev.para.spawnselector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SpawnData {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File DATA_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("spawn-selector")
            .resolve("spawndata.json")
            .toFile();

    private static Set<UUID> selectedPlayers = new HashSet<>();

    public static void load() {
        DATA_FILE.getParentFile().mkdirs();

        if (!DATA_FILE.exists()) {
            selectedPlayers = new HashSet<>();
            save();
        } else {
            try (FileReader reader = new FileReader(DATA_FILE)) {
                selectedPlayers = GSON.fromJson(reader, new TypeToken<Set<UUID>>(){}.getType());
                if (selectedPlayers == null) {
                    selectedPlayers = new HashSet<>();
                }
            } catch (Exception e) {
                SpawnSelectorMod.LOGGER.error("Failed to load spawn data", e);
                selectedPlayers = new HashSet<>();
            }
        }
    }

    private static void save() {
        try (FileWriter writer = new FileWriter(DATA_FILE)) {
            GSON.toJson(selectedPlayers, writer);
        } catch (Exception e) {
            SpawnSelectorMod.LOGGER.error("Failed to save spawn data", e);
        }
    }

    public static boolean hasSelectedSpawn(ServerPlayer player) {
        return selectedPlayers.contains(player.getUUID());
    }

    public static void markSpawnSelected(ServerPlayer player) {
        selectedPlayers.add(player.getUUID());
        save();
    }

    public static void resetSpawnSelection(ServerPlayer player) {
        selectedPlayers.remove(player.getUUID());
        save();
    }
}
