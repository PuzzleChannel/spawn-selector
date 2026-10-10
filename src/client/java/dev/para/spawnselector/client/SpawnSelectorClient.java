package dev.para.spawnselector.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.ArrayList;
import java.util.List;

public class SpawnSelectorClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SpawnPayloads.OpenMenu.TYPE, (payload, context) -> {
            try {
                JsonObject root = JsonParser.parseString(payload.json()).getAsJsonObject();
                JsonArray array = root.getAsJsonArray("teams");
                List<SpawnTeamView> teams = new ArrayList<>();
                for (int i = 0; i < Math.min(3, array.size()); i++) {
                    JsonObject item = array.get(i).getAsJsonObject();
                    teams.add(new SpawnTeamView(item.get("name").getAsString(),
                            item.get("description").getAsString(), item.get("image").getAsString(),
                            item.get("count").getAsInt(), item.get("max").getAsInt(), item.get("color").getAsString()));
                }
                String message = root.has("message") ? root.get("message").getAsString() : "";
                context.client().gui.setScreen(new SpawnSelectorScreen(teams, message));
            } catch (Exception e) {
                context.client().gui.setScreen(new SpawnSelectorScreen(List.of(), "Не удалось загрузить список территорий."));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(SpawnPayloads.CloseMenu.TYPE,
                (payload, context) -> context.client().gui.setScreen(null));
    }

    public record SpawnTeamView(String name, String description, String image, int count, int max, String color) {
        public String countText() { return max > 0 ? count + "/" + max + " игроков" : count + " игроков"; }
    }
}