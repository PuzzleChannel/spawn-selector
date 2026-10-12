package dev.para.spawnselector.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class SpawnSelectorClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SpawnPayloads.OpenMenu.TYPE, (payload, context) -> {
            try {
                JsonObject root = JsonParser.parseString(payload.json()).getAsJsonObject();
                Map<String, String> menuText = new LinkedHashMap<>();
                if (root.has("menuText") && root.get("menuText").isJsonObject()) {
                    for (var entry : root.getAsJsonObject("menuText").entrySet()) {
                        if (entry.getValue() != null && entry.getValue().isJsonPrimitive())
                            menuText.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                JsonArray array = root.getAsJsonArray("teams");
                List<SpawnTeamView> teams = new ArrayList<>();
                for (int i = 0; i < Math.min(3, array.size()); i++) {
                    JsonObject item = array.get(i).getAsJsonObject();
                    int count = item.get("count").getAsInt();
                    int max = item.get("max").getAsInt();
                    String countTemplate = menuText.getOrDefault(max > 0 ? "playersLimited" : "players", max > 0 ? "{count}/{max} игроков" : "{count} игроков");
                    String countText = countTemplate.replace("{count}", Integer.toString(count)).replace("{max}", Integer.toString(max));
                    teams.add(new SpawnTeamView(item.get("name").getAsString(),
                            item.get("description").getAsString(), item.get("image").getAsString(),
                            count, max, item.get("color").getAsString(), countText,
                            !item.has("available") || item.get("available").getAsBoolean()));
                }
                String message = root.has("message") ? root.get("message").getAsString() : "";
                context.client().gui.setScreen(new SpawnSelectorScreen(teams, message, menuText));
            } catch (Exception e) {
                context.client().gui.setScreen(new SpawnSelectorScreen(List.of(), "Не удалось загрузить список территорий.", Map.of()));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(SpawnPayloads.TeamsUpdate.TYPE, (payload, context) -> {
            try {
                JsonArray updates = JsonParser.parseString(payload.json()).getAsJsonArray();
                context.client().execute(() -> {
                    if (context.client().gui.getScreen() instanceof SpawnSelectorScreen screen)
                        screen.updateTeams(updates);
                });
            } catch (Exception ignored) { }
        });
        ClientPlayNetworking.registerGlobalReceiver(SpawnPayloads.CloseMenu.TYPE,
                (payload, context) -> context.client().gui.setScreen(null));
    }

    public record SpawnTeamView(String name, String description, String image, int count, int max, String color, String countText, boolean available) {}
}