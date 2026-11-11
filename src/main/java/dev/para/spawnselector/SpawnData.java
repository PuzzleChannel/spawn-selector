package dev.para.spawnselector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SpawnData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File DATA_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("spawn-selector").resolve("spawndata.json").toFile();
    private static Set<UUID> selectedPlayers = new HashSet<>();

    public static void load() {
        DATA_FILE.getParentFile().mkdirs();
        if (!DATA_FILE.exists()) {
            selectedPlayers = new HashSet<>();
            save();
            return;
        }
        try (FileReader reader = new FileReader(DATA_FILE)) {
            Set<UUID> loaded = GSON.fromJson(reader, new TypeToken<Set<UUID>>(){}.getType());
            selectedPlayers = loaded == null ? new HashSet<>() : loaded;
        } catch (Exception e) {
            SpawnSelectorMod.LOGGER.error("Не удалось загрузить данные выбора команд; создаю резервную копию.", e);
            try {
                Files.copy(DATA_FILE.toPath(), DATA_FILE.toPath().resolveSibling("spawndata.json.broken"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception backupError) {
                SpawnSelectorMod.LOGGER.error("Не удалось создать резервную копию данных выбора.", backupError);
            }
            selectedPlayers = new HashSet<>();
            save();
        }
    }

    private static void save() {
        try {
            File temp = new File(DATA_FILE.getParentFile(), "spawndata.json.tmp");
            try (FileWriter writer = new FileWriter(temp)) {
                GSON.toJson(selectedPlayers, writer);
            }
            try {
                Files.move(temp.toPath(), DATA_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(temp.toPath(), DATA_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            SpawnSelectorMod.LOGGER.error("Не удалось сохранить данные выбора команд.", e);
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
