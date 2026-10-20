package dev.para.spawnselector.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.para.spawnselector.client.SpawnSelectorClient.SpawnTeamView;
import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SpawnSelectorScreen extends Screen {
    private List<SpawnTeamView> teams;
    private final Map<String, String> menuText;
    private final String message;
    private boolean compactLayout;
    private int hovered = -1;
    private int lastHovered = -1;
    private int cardsX;
    private int cardsY;
    private int cardWidth;
    private int cardHeight;
    private int cardGap;
    private int cardCount;

    private static final Map<Identifier, int[]> IMAGE_SIZES = new ConcurrentHashMap<>();
    private static final int[] ACCENT = {0xFF3B82F6, 0xFFEF4444, 0xFFF59E0B};
    private static final int[] ACCENT_DARK = {0xFF1E3A8A, 0xFF7F1D1D, 0xFF78350F};
    private static final int[] ACCENT_LIGHT = {0xFF93C5FD, 0xFFFCA5A5, 0xFFFDE68A};

    public SpawnSelectorScreen(List<SpawnTeamView> teams, String message, Map<String, String> menuText) {
        super(Component.literal(text(menuText, "windowTitle", "Выбор территории")));
        this.teams = new ArrayList<>(teams);
        this.message = message == null ? "" : message;
        this.menuText = menuText == null ? Map.of() : Map.copyOf(menuText);
    }

    private static String text(Map<String, String> values, String key, String fallback) {
        String value = values == null ? null : values.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String getText(String key, String fallback) {
        return text(this.menuText, key, fallback);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, 0xEE080B12);
        g.fill(0, 0, width, 2, 0x332C3D58);
        g.fill(0, height - 2, width, height, 0x55000000);

        compactLayout = width < 680 || height < 450;
        int titleY = compactLayout ? 8 : 18;
        String title = getText("headerTitle", "ВЫБЕРИТЕ ТЕРРИТОРИЮ");
        String subtitle = getText("headerSubtitle", "Выберите стартовую территорию своей команды");
        drawCentered(g, "§l" + title, width / 2, titleY, 0xFFFFFFFF);
        drawCentered(g, "§7" + subtitle, width / 2, titleY + 15, 0xFFB8C4D6);

        boolean hasMessage = !this.message.isBlank();
        if (hasMessage) {
            drawCentered(g, "§c⚠ " + this.message, width / 2, titleY + 29, 0xFFF87171);
        }

        cardCount = Math.min(this.teams.size(), 3);
        int headerSpace = titleY + (hasMessage ? 43 : 32);
        int outerPadding = width < 720 ? 16 : 28;
        cardGap = width < 800 ? 12 : 18;
        int availableWidth = Math.max(0, width - outerPadding * 2);
        cardWidth = cardCount == 0 ? 0 : Math.min(292,
                (availableWidth - cardGap * (cardCount - 1)) / cardCount);
        cardWidth = Math.max(cardCount == 0 ? 0 : 96, cardWidth);

        int desiredHeight = compactLayout ? 292 : 350;
        cardHeight = Math.min(desiredHeight, Math.max(170, height - headerSpace - 25));
        int totalWidth = cardCount * cardWidth + Math.max(0, cardCount - 1) * cardGap;
        cardsX = (width - totalWidth) / 2;
        int remainingHeight = height - headerSpace - cardHeight - 22;
        cardsY = headerSpace + Math.max(8, remainingHeight / 2);
        hovered = -1;

        for (int i = 0; i < cardCount; i++) {
            int x = cardsX + i * (cardWidth + cardGap);
            boolean cardHovered = inside(mouseX, mouseY, x, cardsY, cardWidth, cardHeight);
            if (cardHovered) hovered = i;
            renderTerritoryCard(g, x, cardsY, cardWidth, cardHeight, this.teams.get(i), i, cardHovered);
        }

        if (cardCount == 0) {
            drawCentered(g, "§c" + getText("errorLoad", "Не удалось загрузить список территорий."),
                    width / 2, height / 2, 0xFFFF7777);
        }

        if (hovered != lastHovered && hovered >= 0) playUiSound(1.45F, 0.18F);
        lastHovered = hovered;
        drawCentered(g, "§8Выбор закрепит территорию и назначит соответствующую команду",
                width / 2, height - 14, 0xFF64748B);
    }

    private void renderTerritoryCard(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                     SpawnTeamView team, int index, boolean isHovered) {
        int accent = ACCENT[index % ACCENT.length];
        int dark = ACCENT_DARK[index % ACCENT_DARK.length];
        int light = ACCENT_LIGHT[index % ACCENT_LIGHT.length];
        int pad = compactLayout ? 8 : 11;
        int contentX = x + pad;
        int contentW = Math.max(1, w - pad * 2);
        int bg = isHovered ? 0xF51A2535 : 0xE9101724;

        g.fill(x, y, x + w, y + h, bg);
        drawOutline(g, x, y, w, h, isHovered ? accent : 0xFF2A3547);
        g.fill(x + 1, y + 1, x + w - 1, y + 4, isHovered ? light : accent);

        int artH = Math.min(compactLayout ? 104 : 130, Math.max(54, (contentW * 529) / 940));
        int artY = y + pad + 3;
        renderTerritoryArt(g, team.buttonIcon(), contentX, artY, contentW, artH, isHovered, accent);

        int badgeX = contentX + 7;
        int badgeY = artY + 7;
        g.fill(badgeX, badgeY, badgeX + 31, badgeY + 17, 0xCC080B12);
        drawOutline(g, badgeX, badgeY, 31, 17, 0x66FFFFFF);
        drawCentered(g, String.format("%02d", index + 1), badgeX + 15, badgeY + 4, light);

        int nameY = artY + artH + (compactLayout ? 7 : 10);
        String displayName = fitText(team.name(), contentW - 8);
        drawCentered(g, "§l" + displayName, x + w / 2, nameY,
                team.selectable() ? 0xFFFFFFFF : 0xFF9299A5);

        int pillY = nameY + (compactLayout ? 15 : 18);
        int pillH = compactLayout ? 17 : 20;
        int pillW = Math.max(52, contentW - 18);
        int pillX = x + (w - pillW) / 2;
        g.fill(pillX, pillY, pillX + pillW, pillY + pillH, 0x77070B12);
        drawOutline(g, pillX, pillY, pillW, pillH, 0x332D3C50);
        drawCentered(g, "§a● §f" + fitText(team.countLabel(), pillW - 10),
                x + w / 2, pillY + (pillH - 8) / 2, 0xFFFFFFFF);

        int buttonH = compactLayout ? 24 : 29;
        int buttonY = y + h - pad - buttonH;
        int descriptionY = pillY + pillH + (compactLayout ? 7 : 10);
        int descriptionH = Math.max(0, buttonY - descriptionY - 8);
        drawMultilineText(g, team.description(), contentX + 2, descriptionY,
                Math.max(1, contentW - 4), descriptionH, 0xFFCBD5E1);

        int buttonBg = !team.selectable() ? 0x66302A2A : (isHovered ? accent : dark);
        int buttonBorder = !team.selectable() ? 0xFF563D42 : (isHovered ? light : accent);
        g.fill(contentX, buttonY, contentX + contentW, buttonY + buttonH, buttonBg);
        drawOutline(g, contentX, buttonY, contentW, buttonH, buttonBorder);
        String buttonText = team.selectable()
                ? getText("chooseButton", "ВЫБРАТЬ ТЕРРИТОРИЮ")
                : getText("unavailable", "НЕДОСТУПНО");
        drawCentered(g, "§l" + fitText(buttonText, contentW - 10), x + w / 2,
                buttonY + (buttonH - 8) / 2, team.selectable() ? 0xFFFFFFFF : 0xFF9797A1);
    }

    private void renderTerritoryArt(GuiGraphicsExtractor g, String iconPath, int x, int y,
                                    int w, int h, boolean isHovered, int accentColor) {
        if (iconPath != null && !iconPath.isBlank()) {
            try {
                String cleanPath = iconPath.trim();
                Identifier id = cleanPath.contains(":")
                        ? Identifier.parse(cleanPath)
                        : Identifier.fromNamespaceAndPath("spawnselector",
                            cleanPath.startsWith("textures/") ? cleanPath : "textures/gui/spawn_selector/" + cleanPath);

                int[] size = IMAGE_SIZES.computeIfAbsent(id, key -> {
                    try {
                        var resource = Minecraft.getInstance().getResourceManager().getResource(key);
                        if (resource.isPresent()) {
                            try (var stream = resource.get().open()) {
                                var image = ImageIO.read(stream);
                                if (image != null) return new int[]{image.getWidth(), image.getHeight()};
                            }
                        }
                    } catch (Exception ignored) { }
                    return new int[]{0, 0};
                });

                if (size[0] > 0 && size[1] > 0) {
                    g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0.0F, 0.0F,
                            w, h, size[0], size[1], size[0], size[1]);
                    drawOutline(g, x, y, w, h, isHovered ? accentColor : 0xFF344258);
                    return;
                }
            } catch (RuntimeException ignored) {
                // Use the themed placeholder if the image is not available.
            }
        }

        g.fill(x, y, x + w, y + h, isHovered ? 0xFF1C2A3D : 0xFF121B2A);
        drawCentered(g, "§8ПРЕВЬЮ ТЕРРИТОРИИ", x + w / 2, y + h / 2 - 4, accentColor);
        drawOutline(g, x, y, w, h, isHovered ? accentColor : 0xFF344258);
    }

    private void drawMultilineText(GuiGraphicsExtractor g, String text, int x, int y,
                                   int maxW, int maxH, int color) {
        if (text == null || text.isBlank() || maxH < this.font.lineHeight) return;
        String[] words = text.trim().split("\\s+");
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();

        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (this.font.width(candidate) <= maxW) {
                line = new StringBuilder(candidate);
            } else {
                if (line.length() > 0) lines.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());

        int lineHeight = compactLayout ? 9 : 10;
        int maxLines = Math.max(1, maxH / lineHeight);
        for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
            String value = lines.get(i);
            if (i == maxLines - 1 && lines.size() > maxLines) {
                while (!value.isEmpty() && this.font.width(value + "…") > maxW) {
                    value = value.substring(0, value.length() - 1);
                }
                value += "…";
            }
            g.text(this.font, value, x, y + i * lineHeight, color, true);
        }
    }

    private String fitText(String value, int maxWidth) {
        if (value == null) return "";
        if (this.font.width(value) <= maxWidth) return value;
        String shortened = value;
        while (!shortened.isEmpty() && this.font.width(shortened + "…") > maxWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened.isEmpty() ? "" : shortened + "…";
    }

    private void drawCentered(GuiGraphicsExtractor g, String value, int centerX, int y, int color) {
        g.text(this.font, value, centerX - this.font.width(value) / 2, y, color, true);
    }

    private void drawOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public void updateTeams(JsonArray updates) {
        if (updates == null) return;
        int count = Math.min(Math.min(updates.size(), this.teams.size()), 3);
        for (int i = 0; i < count; i++) {
            try {
                JsonObject update = updates.get(i).getAsJsonObject();
                SpawnTeamView old = this.teams.get(i);
                int players = update.has("count") ? update.get("count").getAsInt() : old.count();
                int max = update.has("max") ? update.get("max").getAsInt() : old.max();
                boolean available = !update.has("available") || update.get("available").getAsBoolean();
                String template = getText(max > 0 ? "playersLimited" : "players",
                        max > 0 ? "{count}/{max} игроков" : "{count} игроков");
                String label = template.replace("{count}", Integer.toString(players))
                        .replace("{max}", Integer.toString(max));
                this.teams.set(i, new SpawnTeamView(old.id(), old.name(), old.description(),
                        old.buttonIcon(), players, max, label, available));
            } catch (RuntimeException ignored) {
                // Ignore one malformed status entry instead of closing the selector.
            }
        }
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void playUiSound(float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (event.button() != 0) return super.mouseClicked(event, doubleClick);

        for (int i = 0; i < cardCount; i++) {
            int x = cardsX + i * (cardWidth + cardGap);
            if (!inside(mouseX, mouseY, x, cardsY, cardWidth, cardHeight)) continue;
            SpawnTeamView team = this.teams.get(i);
            if (!team.selectable()) {
                playUiSound(1.0F, 0.45F);
                return true;
            }
            playUiSound(0.9F, 0.9F);
            ClientPlayNetworking.send(new SpawnPayloads.Choose(team.id()));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void onClose() {
        // A first-time player must choose one of the three territories explicitly.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
