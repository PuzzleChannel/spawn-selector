package dev.para.spawnselector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SpawnConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("spawn-selector").resolve("spawnpoints.json").toFile();

    public static List<SpawnPoint> spawnPoints = new ArrayList<>();
    public static int radius = 10;
    /** 0 disables the absolute per-team cap. */
    public static int maxPlayersPerTeam = 0;
    /** A choice is blocked if it would leave that team this many players above the smallest team. */
    public static int maxTeamImbalance = 3;

    public static void load() {
        CONFIG_FILE.getParentFile().mkdirs();
        if (!CONFIG_FILE.exists()) {
            createDefault();
            save();
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            validate(data);
            spawnPoints = new ArrayList<>(data.spawnPoints);
            // Update only the previous built-in descriptions; preserve any server-owner edits.
            boolean descriptionsUpdated = false;
            for (SpawnPoint point : spawnPoints) {
                if ("Зелёная долина у леса. Подходит для спокойного старта и сбора ресурсов.".equals(point.description)) {
                    point.description = "Лес рядом, вокруг есть место для первых построек.";
                    descriptionsUpdated = true;
                } else if ("Открытая равнина для строительства базы и развития поселения.".equals(point.description)) {
                    point.description = "Ровное место для базы, полей и поселения.";
                    descriptionsUpdated = true;
                } else if ("Высокогорная местность с обзором и доступом к каменным ресурсам.".equals(point.description)) {
                    point.description = "Горы, камень и хороший обзор окрестностей.";
                    descriptionsUpdated = true;
                }
            }
            radius = data.radius;
            maxPlayersPerTeam = data.maxPlayersPerTeam;
            maxTeamImbalance = data.maxTeamImbalance;
            if (descriptionsUpdated) save();
        } catch (Exception e) {
            SpawnSelectorMod.LOGGER.error("Не удалось загрузить конфигурацию точек появления. Создаю резервную копию и использую настройки по умолчанию.", e);
            try {
                Files.copy(CONFIG_FILE.toPath(), CONFIG_FILE.toPath().resolveSibling("spawnpoints.json.broken"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception backupError) {
                SpawnSelectorMod.LOGGER.error("Не удалось сохранить резервную копию повреждённой конфигурации.", backupError);
            }
            createDefault();
            save();
        }
    }

    private static void validate(ConfigData data) {
        if (data == null || data.spawnPoints == null || data.spawnPoints.size() != 3)
            throw new IllegalArgumentException("В конфигурации должны быть ровно три команды.");
        if (data.radius < 0 || data.radius > 128)
            throw new IllegalArgumentException("radius должен быть от 0 до 128.");
        if (data.maxPlayersPerTeam < 0 || data.maxPlayersPerTeam > 100000)
            throw new IllegalArgumentException("maxPlayersPerTeam должен быть от 0 до 100000.");
        if (data.maxTeamImbalance < 0 || data.maxTeamImbalance > 100000)
            throw new IllegalArgumentException("maxTeamImbalance должен быть от 0 до 100000.");

        Set<String> teams = new HashSet<>();
        for (SpawnPoint point : data.spawnPoints) {
            if (point == null || point.name == null || point.name.isBlank() || point.name.length() > 48
                    || point.description == null || point.description.length() > 240
                    || point.image == null || !point.image.matches("[a-z0-9_./-]{1,100}")
                    || point.team == null || !point.team.matches("[a-zA-Z0-9_.-]{1,16}")
                    || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                    || !Float.isFinite(point.yaw))
                throw new IllegalArgumentException("Обнаружена некорректная точка появления.");
            if (!teams.add(point.team))
                throw new IllegalArgumentException("Названия команд должны быть уникальными.");
        }
    }

    private static void createDefault() {
        spawnPoints = new ArrayList<>(List.of(
            new SpawnPoint("Лесная долина", "Лес рядом, вокруг есть место для первых построек.", "textures/gui/spawn_selector/forest.png", 0, 100, 0, 0, "team1", "green"),
            new SpawnPoint("Равнинное поселение", "Ровное место для базы, полей и поселения.", "textures/gui/spawn_selector/plains.png", 100, 100, 100, 0, "team2", "blue"),
            new SpawnPoint("Горное плато", "Горы, камень и хороший обзор окрестностей.", "textures/gui/spawn_selector/mountains.png", -100, 100, 100, 0, "team3", "gold")
        ));
        radius = 10;
        maxPlayersPerTeam = 0;
        maxTeamImbalance = 3;
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_FILE.toPath().getParent());
            File temp = new File(CONFIG_FILE.getParentFile(), "spawnpoints.json.tmp");
            ConfigData data = new ConfigData();
            data.spawnPoints = spawnPoints;
            data.radius = radius;
            data.maxPlayersPerTeam = maxPlayersPerTeam;
            data.maxTeamImbalance = maxTeamImbalance;
            try (FileWriter writer = new FileWriter(temp)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(temp.toPath(), CONFIG_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(temp.toPath(), CONFIG_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            SpawnSelectorMod.LOGGER.error("Не удалось сохранить конфигурацию точек появления.", e);
        }
    }

    public static class ConfigData {
        public List<SpawnPoint> spawnPoints;
        public int radius = 10;
        public int maxPlayersPerTeam = 0;
        public int maxTeamImbalance = 3;
    }

    public static class SpawnPoint {
        public String name;
        public String description;
        public String image;
        public double x, y, z;
        public float yaw;
        public String team;
        public String color;

        public SpawnPoint(String name, String description, String image, double x, double y, double z,
                          float yaw, String team, String color) {
            this.name = name;
            this.description = description;
            this.image = image;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.team = team;
            this.color = color;
        }
    }
}
