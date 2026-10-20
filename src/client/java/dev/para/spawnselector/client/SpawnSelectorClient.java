package dev.para.spawnselector.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpawnSelectorClient implements ClientModInitializer {
    private static volatile SpawnSelectorScreen activeScreen;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SpawnPayloads.OpenMenu.TYPE, (payload, context) -> {
            try {
                JsonObject root = JsonParser.parseString(payload.json()).getAsJsonObject();
                Map<String, String> menuText = new LinkedHashMap<>();
                if (root.has("menuText") && root.get("menuText").isJsonObject()) {
                    for (var entry : root.getAsJsonObject("menuText").entrySet()) {
                        if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                            menuText.put(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }

                JsonArray array = root.has("teams") && root.get("teams").isJsonArray()
                        ? root.getAsJsonArray("teams") : new JsonArray();
                List<SpawnTeamView> teams = new ArrayList<>();
                for (int i = 0; i < Math.min(3, array.size()); i++) {
                    JsonObject item = array.get(i).getAsJsonObject();
                    int count = item.has("count") ? item.get("count").getAsInt() : 0;
                    int max = item.has("max") ? item.get("max").getAsInt() : 0;
                    boolean available = !item.has("available") || item.get("available").getAsBoolean();
                    String name = stringValue(item, "name", "Территория " + (i + 1));
                    String description = stringValue(item, "description", "");
                    // Fixed images follow the configured territory order: Europe, Africa, Australia.
                    String previewImage = switch (i) {
                        case 0 -> "textures/gui/spawn_selector/forest.png";
                        case 1 -> "textures/gui/spawn_selector/plains.png";
                        default -> "textures/gui/spawn_selector/mountains.png";
                    };
                    String template = menuText.getOrDefault(max > 0 ? "playersLimited" : "players",
                            max > 0 ? "{count}/{max} игроков" : "{count} игроков");
                    String countLabel = template.replace("{count}", Integer.toString(count))
                            .replace("{max}", Integer.toString(max));
                    teams.add(new SpawnTeamView(i, name, description, previewImage,
                            count, max, countLabel, available));
                }

                String message = root.has("message") ? root.get("message").getAsString() : "";
                context.client().execute(() -> {
                    activeScreen = new SpawnSelectorScreen(teams, message, menuText);
                    context.client().gui.setScreen(activeScreen);
                });
            } catch (Exception e) {
                context.client().execute(() -> {
                    activeScreen = new SpawnSelectorScreen(List.of(),
                            "Не удалось загрузить список территорий.", Map.of());
                    context.client().gui.setScreen(activeScreen);
                });
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(SpawnPayloads.TeamsUpdate.TYPE, (payload, context) -> {
            try {
                JsonArray updates = JsonParser.parseString(payload.json()).getAsJsonArray();
                context.client().execute(() -> {
                    SpawnSelectorScreen screen = activeScreen;
                    if (screen != null) screen.updateTeams(updates);
                });
            } catch (Exception ignored) {
                // Ignore a malformed status update without interrupting the game.
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(SpawnPayloads.CloseMenu.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    activeScreen = null;
                    context.client().gui.setScreen(null);
                }));
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : fallback;
    }

    public record SpawnTeamView(int id, String name, String description, String buttonIcon,
                                int count, int max, String countLabel, boolean selectable) {}
}
