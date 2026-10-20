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

    private static final Map<String, Boolean> ICON_VALIDITY = new ConcurrentHashMap<>();
    private static final int[] ACCENT = {0xFF3B82F6, 0xFFEF4444, 0xFFF59E0B};
    private static final int[] ACCENT_DARK = {0xFF1E3A8A, 0xFF7F1D1D, 0xFF78350F};
    private static final int[] ACCENT_LIGHT = {0xFF93C5FD, 0xFFFCA5A5, 0xFFFDE68A};

    public SpawnSelectorScreen(List<SpawnTeamView> teams, String message, Map<String, String> menuText) {
        super(Component.literal(text(menuText, "windowTitle", "Выбор территории")));
        this.teams = new ArrayList<>(teams);
        this.message = message == null ? "" : message;
        this.menuText = menuText == null ? Map.of() : menuText;
    }

    @Override
    protected void init() {
        super.init();
        this.compactLayout = this.height < 450;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void renderBackground(Object graphics, int mouseX, int mouseY, float delta) {
        GuiGraphicsExtractor.fill(graphics, 0, 0, this.width, this.height, 0xEE080B12);
        // A subtle, restrained vignette made from translucent horizontal bands.
        GuiGraphicsExtractor.fill(graphics, 0, 0, this.width, 2, 0x332C3D58);
        GuiGraphicsExtractor.fill(graphics, 0, this.height - 2, this.width, this.height, 0x55000000);
    }

    @Override
    public void render(Object graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        this.hovered = -1;

        int titleY = compactLayout ? 8 : 18;
        String title = getText("headerTitle", "ВЫБЕРИТЕ ТЕРРИТОРИЮ");
        String subtitle = getText("headerSubtitle", "Выберите сторону, с которой начнётся ваша история");

        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§l" + title, this.width / 2, titleY, 0xFFFFFFFF);
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§7" + subtitle, this.width / 2, titleY + 15, 0xFFB8C4D6);

        int count = Math.min(this.teams.size(), 3);
        boolean hasMessage = !this.message.isBlank();
        if (hasMessage) {
            GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§c⚠ " + this.message,
                    this.width / 2, titleY + 29, 0xFFF87171);
        }

        int headerSpace = titleY + (hasMessage ? 43 : 32);
        int outerPadding = this.width < 720 ? 16 : 28;
        int cardGap = this.width < 800 ? 12 : 18;
        int availableWidth = Math.max(0, this.width - outerPadding * 2);
        int cardWidth = count == 0 ? 0 : Math.min(292, (availableWidth - cardGap * (count - 1)) / count);
        cardWidth = Math.max(count == 0 ? 0 : 118, cardWidth);

        int desiredCardHeight = compactLayout ? 292 : 350;
        int cardHeight = Math.min(desiredCardHeight, Math.max(170, this.height - headerSpace - 25));
        int totalWidth = count * cardWidth + Math.max(0, count - 1) * cardGap;
        int startX = (this.width - totalWidth) / 2;
        int remainingHeight = this.height - headerSpace - cardHeight - 22;
        int startY = headerSpace + Math.max(8, remainingHeight / 2);

        for (int i = 0; i < count; i++) {
            int x = startX + i * (cardWidth + cardGap);
            boolean cardHovered = mouseX >= x && mouseX < x + cardWidth
                    && mouseY >= startY && mouseY < startY + cardHeight;
            if (cardHovered) this.hovered = i;
            renderTerritoryCard(graphics, x, startY, cardWidth, cardHeight,
                    this.teams.get(i), i, cardHovered);
        }

        if (count == 0) {
            GuiGraphicsExtractor.drawCenteredString(graphics, this.font,
                    "§c" + getText("errorLoad", "Не удалось загрузить список территорий."),
                    this.width / 2, this.height / 2, 0xFFFF7777);
        }

        if (this.hovered != this.lastHovered && this.hovered >= 0) {
            playHoverSound();
        }
        this.lastHovered = this.hovered;

        String footer = "Выбор закрепит территорию и назначит соответствующую команду";
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§8" + footer,
                this.width / 2, this.height - 14, 0xFF64748B);
    }

    private void renderTerritoryCard(Object graphics, int x, int y, int w, int h,
                                     SpawnTeamView team, int index, boolean isHovered) {
        int accent = ACCENT[index % ACCENT.length];
        int dark = ACCENT_DARK[index % ACCENT_DARK.length];
        int light = ACCENT_LIGHT[index % ACCENT_LIGHT.length];
        int pad = compactLayout ? 8 : 11;
        int contentX = x + pad;
        int contentW = Math.max(1, w - pad * 2);
        int bg = isHovered ? 0xF51A2535 : 0xE9101724;

        GuiGraphicsExtractor.fill(graphics, x, y, x + w, y + h, bg);
        drawOutline(graphics, x, y, w, h, isHovered ? accent : 0xFF2A3547);
        GuiGraphicsExtractor.fill(graphics, x + 1, y + 1, x + w - 1, y + 4, isHovered ? light : accent);

        int artH = Math.min(compactLayout ? 104 : 130, Math.max(54, (contentW * 9) / 16));
        int artY = y + pad + 3;
        renderTerritoryArt(graphics, team.buttonIcon(), contentX, artY, contentW, artH,
                isHovered, accent);

        // Number badge creates a clear visual identity without cluttering the artwork.
        int badgeX = contentX + 7;
        int badgeY = artY + 7;
        GuiGraphicsExtractor.fill(graphics, badgeX, badgeY, badgeX + 31, badgeY + 17, 0xCC080B12);
        drawOutline(graphics, badgeX, badgeY, 31, 17, 0x664FFFFFFF);
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, String.format("%02d", index + 1),
                badgeX + 15, badgeY + 4, light);

        int nameY = artY + artH + (compactLayout ? 7 : 10);
        String displayName = fitText(team.name(), contentW - 8);
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§l" + displayName,
                x + w / 2, nameY, team.selectable() ? 0xFFFFFFFF : 0xFF9299A5);

        int pillY = nameY + (compactLayout ? 15 : 18);
        int pillH = compactLayout ? 17 : 20;
        int pillW = Math.max(52, contentW - 18);
        int pillX = x + (w - pillW) / 2;
        GuiGraphicsExtractor.fill(graphics, pillX, pillY, pillX + pillW, pillY + pillH, 0x77070B12);
        drawOutline(graphics, pillX, pillY, pillW, pillH, 0x332D3C50);
        String countLabel = team.countLabel() == null ? "" : team.countLabel();
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§a● §f" + fitText(countLabel, pillW - 10),
                x + w / 2, pillY + (pillH - 8) / 2, 0xFFFFFFFF);

        int buttonH = compactLayout ? 24 : 29;
        int buttonY = y + h - pad - buttonH;
        int descriptionY = pillY + pillH + (compactLayout ? 7 : 10);
        int descriptionH = Math.max(0, buttonY - descriptionY - 8);
        drawMultilineText(graphics, team.description(), contentX + 2, descriptionY,
                Math.max(1, contentW - 4), descriptionH, 0xFFCBD5E1);

        boolean selectable = team.selectable();
        int buttonBg = !selectable ? 0x66302A2A : (isHovered ? accent : dark);
        int buttonBorder = !selectable ? 0xFF563D42 : (isHovered ? light : accent);
        GuiGraphicsExtractor.fill(graphics, contentX, buttonY, contentX + contentW, buttonY + buttonH, buttonBg);
        drawOutline(graphics, contentX, buttonY, contentW, buttonH, buttonBorder);
        String buttonText = selectable
                ? getText("chooseButton", "ВЫБРАТЬ ТЕРРИТОРИЮ")
                : getText("unavailable", "НЕДОСТУПНО");
        int buttonColor = selectable ? 0xFFFFFFFF : 0xFF9797A1;
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§l" + fitText(buttonText, contentW - 10),
                x + w / 2, buttonY + (buttonH - 8) / 2, buttonColor);
    }

    private void renderTerritoryArt(Object graphics, String iconPath, int x, int y, int w, int h,
                                    boolean isHovered, int accentColor) {
        if (iconPath != null && !iconPath.isBlank()) {
            try {
                String cleanPath = iconPath.trim();
                Identifier id = cleanPath.contains(":")
                        ? Identifier.parse(cleanPath)
                        : Identifier.fromNamespaceAndPath("spawnselector",
                            cleanPath.startsWith("textures/") ? cleanPath : "textures/gui/spawn_selector/" + cleanPath);

                Boolean valid = ICON_VALIDITY.get(cleanPath);
                if (valid == null) {
                    try (var stream = Minecraft.getInstance().getResourceManager()
                            .getResource(id).orElseThrow().open()) {
                        valid = ImageIO.read(stream) != null;
                    } catch (Exception e) {
                        valid = false;
                    }
                    ICON_VALIDITY.put(cleanPath, valid);
                }

                if (valid) {
                    RenderPipelines.COLOR_TEXTURE.get();
                    GuiGraphicsExtractor.blit(graphics, id, x, y, 0.0f, 0.0f, w, h, w, h);
                    drawOutline(graphics, x, y, w, h, isHovered ? accentColor : 0xFF344258);
                    return;
                }
            } catch (Exception ignored) {
                // Use the themed placeholder below if a custom image cannot be loaded.
            }
        }

        GuiGraphicsExtractor.fill(graphics, x, y, x + w, y + h,
                isHovered ? 0xFF1C2A3D : 0xFF121B2A);
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§8ПРЕВЬЮ ТЕРРИТОРИИ",
                x + w / 2, y + h / 2 - 4, accentColor);
        drawOutline(graphics, x, y, w, h, isHovered ? accentColor : 0xFF344258);
    }

    private void drawMultilineText(Object graphics, String text, int x, int y, int maxW, int maxH, int color) {
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
        int drawLines = Math.min(lines.size(), maxLines);
        for (int i = 0; i < drawLines; i++) {
            String value = lines.get(i);
            if (i == maxLines - 1 && lines.size() > maxLines) {
                while (!value.isEmpty() && this.font.width(value + "…") > maxW) {
                    value = value.substring(0, value.length() - 1);
                }
                value += "…";
            }
            GuiGraphicsExtractor.drawString(graphics, this.font, value, x, y + i * lineHeight, color, false);
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

    private static String text(Map<String, String> values, String key, String fallback) {
        if (values == null) return fallback;
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String getText(String key, String fallback) {
        return text(this.menuText, key, fallback);
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
                // Ignore a malformed individual update instead of closing the selector.
            }
        }
    }

    private void drawOutline(Object graphics, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        GuiGraphicsExtractor.fill(graphics, x, y, x + w, y + 1, color);
        GuiGraphicsExtractor.fill(graphics, x, y + h - 1, x + w, y + h, color);
        GuiGraphicsExtractor.fill(graphics, x, y + 1, x + 1, y + h - 1, color);
        GuiGraphicsExtractor.fill(graphics, x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && this.hovered >= 0 && this.hovered < this.teams.size()) {
            SpawnTeamView team = this.teams.get(this.hovered);
            if (team.selectable()) {
                playClickSound();
                ClientPlayNetworking.send(new SpawnPayloads.Choose(team.id()));
                return true;
            } else {
                playClickSound();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void playClickSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private void playHoverSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.35F, 1.6F));
    }
}
