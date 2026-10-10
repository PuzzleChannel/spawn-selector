package dev.para.spawnselector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class SpawnConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("spawn-selector")
            .resolve("spawnpoints.json")
            .toFile();

    public static List<SpawnPoint> spawnPoints = new ArrayList<>();
    public static int radius = 10;

    public static void load() {
        CONFIG_FILE.getParentFile().mkdirs();

        if (!CONFIG_FILE.exists()) {
            createDefault();
            save();
        } else {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                ConfigData data = GSON.fromJson(reader, ConfigData.class);
                spawnPoints = data.spawnPoints;
                radius = data.radius;
            } catch (Exception e) {
                SpawnSelectorMod.LOGGER.error("Failed to load config", e);
                createDefault();
            }
        }
    }

    private static void createDefault() {
        spawnPoints = new ArrayList<>(List.of(
            new SpawnPoint("Спавн 1", 0, 100, 0, 0, "team1"),
            new SpawnPoint("Спавн 2", 100, 100, 100, 0, "team2"),
            new SpawnPoint("Спавн 3", -100, 100, 100, 0, "team3"),
            new SpawnPoint("Спавн 4", 100, 100, -100, 0, "team4"),
            new SpawnPoint("Спавн 5", -100, 100, -100, 0, "team5")
        ));
        radius = 10;
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            ConfigData data = new ConfigData();
            data.spawnPoints = spawnPoints;
            data.radius = radius;
            GSON.toJson(data, writer);
        } catch (Exception e) {
            SpawnSelectorMod.LOGGER.error("Failed to save config", e);
        }
    }

    public static class ConfigData {
        public List<SpawnPoint> spawnPoints;
        public int radius;
    }

    public static class SpawnPoint {
        public String name;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public String team;
        public String color;

        public SpawnPoint(String name, double x, double y, double z, float yaw, String team) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.team = team;
            this.color = null;
        }
    }
}
