package dev.para.spawnselector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.lang.reflect.Field;

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
    public static MenuText menuText = new MenuText();

    public static void load() {
        CONFIG_FILE.getParentFile().mkdirs();
        if (!CONFIG_FILE.exists()) {
            createDefault();
            save();
            return;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            ConfigData data = GSON.fromJson(root, ConfigData.class);
            validate(data);
            spawnPoints = new ArrayList<>(data.spawnPoints);
            boolean menuTextAdded = !root.has("menuText") || root.get("menuText").isJsonNull();
            menuText = menuTextAdded || data.menuText == null ? new MenuText() : data.menuText;
            boolean menuTextNormalized = normalizeMenuText(menuText, new MenuText());
            // Migrate an old saved subtitle so the removed free-survival option is not mentioned.
            boolean obsoleteFreeText = menuText.headerSubtitle != null
                    && menuText.headerSubtitle.toLowerCase(java.util.Locale.ROOT).contains("свободное выживание");
            if (obsoleteFreeText) menuText.headerSubtitle = new MenuText().headerSubtitle;
            // Update only the previous built-in descriptions; preserve any server-owner edits.
            boolean descriptionsUpdated = false;
            boolean teamIdsUpdated = false;
            String[] oldTeamIds = {"team1", "team2", "team3"};
            String[] newTeamIds = {"europe", "africa", "avstralia"};
            for (int i = 0; i < spawnPoints.size(); i++) {
                SpawnPoint point = spawnPoints.get(i);
                if (oldTeamIds[i].equals(point.team)) {
                    point.team = newTeamIds[i];
                    teamIdsUpdated = true;
                }
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
            if (descriptionsUpdated || teamIdsUpdated || menuTextAdded || menuTextNormalized || obsoleteFreeText) save();
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

    /**
     * Gson can set explicitly-null JSON values to null, which would otherwise make
     * the client fall back to its own hard-coded strings. Keep the server config
     * authoritative by filling every missing/null text entry from server defaults.
     */
    private static boolean normalizeMenuText(MenuText target, MenuText defaults) {
        boolean changed = false;
        try {
            for (Field field : MenuText.class.getDeclaredFields()) {
                if (field.getType() != String.class) continue;
                field.setAccessible(true);
                if (field.get(target) == null) {
                    field.set(target, field.get(defaults));
                    changed = true;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Не удалось восстановить текст конфигурации меню.", e);
        }
        return changed;
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

        if (data.menuText != null) {
            for (Field field : MenuText.class.getDeclaredFields()) {
                if (field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(data.menuText);
                    if (value instanceof String s && s.length() > 2000) {
                        throw new IllegalArgumentException("Поле menuText." + field.getName() + " превышает 2000 символов.");
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Не удалось проверить текст конфигурации меню.", e);
                }
            }
        }

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
            new SpawnPoint("Лесная долина", "Лес рядом, вокруг есть место для первых построек.", "textures/gui/spawn_selector/forest.png", 0, 100, 0, 0, "europe", "green"),
            new SpawnPoint("Равнинное поселение", "Ровное место для базы, полей и поселения.", "textures/gui/spawn_selector/plains.png", 100, 100, 100, 0, "africa", "blue"),
            new SpawnPoint("Горное плато", "Горы, камень и хороший обзор окрестностей.", "textures/gui/spawn_selector/mountains.png", -100, 100, 100, 0, "avstralia", "gold")
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
            data.menuText = menuText;
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
        public MenuText menuText = new MenuText();
    }

    public static class MenuText {
        public String windowTitle = "Выбор территории";
        public String headerTitle = "ВЫБОР ТЕРРИТОРИИ";
        public String headerSubtitle = "Выберите стартовую территорию своей команды";
        public String chooseButton = "Выбрать";
        public String emptyPreviewTitle = "Выберите вариант";
        public String emptyPreviewSubtitle = "чтобы увидеть описание";
        public String loading = "Загрузка…";
        public String confirmTitle = "ПОДТВЕРЖДЕНИЕ ВЫБОРА";
        public String confirmButton = "Подтвердить";
        public String backButton = "Назад";
        public String players = "{count} игроков";
        public String playersLimited = "{count}/{max} игроков";
        public String unavailable = "Недоступно";
        public String unavailableMessage = "Эта территория сейчас недоступна. Выберите другую.";
        // GUI colors accept #RRGGBB or #AARRGGBB.
        public String styleAccent = "#FFD46A";
        public String stylePanel = "#373737";
        public String styleCard = "#303030";
        public String styleConfirmButton = "#426D45";
        public String styleBackButton = "#6B4141";
        public String errorLoad = "Не удалось загрузить список территорий.";
        public String errorTeleport = "Не удалось установить точку появления. Попробуйте ещё раз.";
        public String errorInvalidChoice = "Этот вариант выбора недоступен.";
        public String errorTeamUnavailable = "Эта команда временно недоступна. Сообщите администрации.";
        public String errorTeamFull = "Территория «{name}» уже заполнена ({count}/{max}). Выберите другую территорию.";
        public String errorTeamImbalanced = "На территории «{name}» сейчас слишком много игроков. Выберите менее заполненную территорию.";
        public String errorInvalidY = "Точка появления настроена некорректно. Сообщите администрации.";
        public String errorNoSurface = "Не удалось найти подходящее место на поверхности этой территории. Сообщите администрации.";
        public String errorAssignTeam = "Не удалось назначить команду. Попробуйте ещё раз или обратитесь к администрации.";
        public String errorSpawnpoint = "Не удалось установить точку появления. Попробуйте ещё раз.";
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
