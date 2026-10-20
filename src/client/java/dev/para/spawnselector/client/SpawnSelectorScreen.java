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
    private String message;
    private boolean compactLayout;
    private int hovered = -1;
    private int lastHovered = -1;
    private int cardsX;
    private int cardsY;
    private int cardWidth;
    private int cardHeight;
    private int cardGap;
    private int cardCount;
    private int pendingSelection = -1;
    private boolean submittingSelection;
    private int confirmModalX, confirmModalY, confirmModalWidth, confirmModalHeight;
    private int confirmButtonX, confirmButtonY, confirmButtonWidth, confirmButtonHeight;
    private int backButtonX, backButtonY, backButtonWidth, backButtonHeight;

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
        drawCentered(g, getText("footerHint", "Выбор закрепит территорию и назначит соответствующую команду"),
                width / 2, height - 14, 0xFF64748B);

        renderConfirmation(g, mouseX, mouseY);
    }

    private void renderConfirmation(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (pendingSelection < 0 || pendingSelection >= this.teams.size()) return;

        SpawnTeamView team = this.teams.get(pendingSelection);
        int accent = ACCENT[pendingSelection % ACCENT.length];
        int light = ACCENT_LIGHT[pendingSelection % ACCENT_LIGHT.length];

        // Darken the whole screen so the choice must be dealt with in this dialog.
        g.fill(0, 0, this.width, this.height, 0xC8080B12);

        confirmModalWidth = Math.max(180, Math.min(440, this.width - 24));
        confirmModalHeight = Math.min(compactLayout ? 198 : 208, Math.max(160, this.height - 20));
        confirmModalX = (this.width - confirmModalWidth) / 2;
        confirmModalY = (this.height - confirmModalHeight) / 2;

        g.fill(confirmModalX, confirmModalY,
                confirmModalX + confirmModalWidth, confirmModalY + confirmModalHeight, 0xFF101827);
        drawOutline(g, confirmModalX, confirmModalY, confirmModalWidth, confirmModalHeight, accent);
        g.fill(confirmModalX + 1, confirmModalY + 1,
                confirmModalX + confirmModalWidth - 1, confirmModalY + 5, light);

        drawCentered(g, "§l" + getText("confirmTitle", "ПОДТВЕРЖДЕНИЕ ВЫБОРА"),
                this.width / 2, confirmModalY + 16, 0xFFF1F5F9);
        drawCentered(g, "§l" + fitText(team.name(), confirmModalWidth - 36),
                this.width / 2, confirmModalY + 42, light);

        String prompt = getText("confirmDescription",
                "Ты выбрал территорию «{name}». Подтвердить этот выбор?")
                .replace("{name}", team.name());
        drawMultilineText(g, prompt, confirmModalX + 18, confirmModalY + 61,
                confirmModalWidth - 36, 23, 0xFFE2E8F0);

        int descriptionY = confirmModalY + 87;
        int descriptionHeight = Math.max(0, Math.min(34, confirmModalY + confirmModalHeight - 72 - descriptionY));
        drawMultilineText(g, team.description(), confirmModalX + 18, descriptionY,
                confirmModalWidth - 36, descriptionHeight, 0xFFB8C4D6);

        String warning = getText("confirmWarning",
                "После подтверждения изменить выбор можно будет только через администрацию.");
        drawMultilineText(g, warning, confirmModalX + 18, confirmModalY + confirmModalHeight - 61,
                confirmModalWidth - 36, 22, 0xFFFDE68A);

        confirmButtonHeight = 25;
        backButtonHeight = 25;
        confirmButtonWidth = Math.min(154, Math.max(94, (confirmModalWidth - 42) / 2));
        backButtonWidth = Math.min(104, Math.max(76, (confirmModalWidth - 42) / 2));
        int buttonGap = 10;
        int groupWidth = backButtonWidth + buttonGap + confirmButtonWidth;
        int groupLeft = confirmModalX + (confirmModalWidth - groupWidth) / 2;
        backButtonX = groupLeft;
        backButtonY = confirmModalY + confirmModalHeight - confirmButtonHeight - 12;
        backButtonHeight = confirmButtonHeight;
        confirmButtonX = backButtonX + backButtonWidth + buttonGap;
        confirmButtonY = backButtonY;

        boolean backHovered = inside(mouseX, mouseY, backButtonX, backButtonY, backButtonWidth, backButtonHeight);
        boolean confirmHovered = inside(mouseX, mouseY, confirmButtonX, confirmButtonY,
                confirmButtonWidth, confirmButtonHeight);

        g.fill(backButtonX, backButtonY, backButtonX + backButtonWidth, backButtonY + backButtonHeight,
                backHovered ? 0xFF2A4161 : 0xFF1C2C43);
        drawOutline(g, backButtonX, backButtonY, backButtonWidth, backButtonHeight, 0xFF344258);
        drawCentered(g, fitText(getText("backButton", "НАЗАД"), backButtonWidth - 10),
                backButtonX + backButtonWidth / 2, backButtonY + 8, 0xFFF1F5F9);

        int confirmBackground = confirmHovered ? light : accent;
        g.fill(confirmButtonX, confirmButtonY, confirmButtonX + confirmButtonWidth,
                confirmButtonY + confirmButtonHeight, confirmBackground);
        drawOutline(g, confirmButtonX, confirmButtonY, confirmButtonWidth, confirmButtonHeight, light);
        drawCentered(g, "§l" + fitText(getText("confirmButton", "ПОДТВЕРДИТЬ"), confirmButtonWidth - 10),
                confirmButtonX + confirmButtonWidth / 2, confirmButtonY + 8, 0xFFFFFFFF);
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
        drawCentered(g, getText("previewPlaceholder", "ПРЕВЬЮ ТЕРРИТОРИИ"),
                x + w / 2, y + h / 2 - 4, accentColor);
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

        if (submittingSelection) return true;
        if (pendingSelection >= 0) {
            // While the confirmation is open, never let clicks pass through to the territory cards.
            if (event.button() != 0) return true;

            if (inside(mouseX, mouseY, backButtonX, backButtonY, backButtonWidth, backButtonHeight)) {
                playUiSound(1.0F, 0.7F);
                pendingSelection = -1;
                message = "";
                return true;
            }

            if (inside(mouseX, mouseY, confirmButtonX, confirmButtonY, confirmButtonWidth, confirmButtonHeight)) {
                SpawnTeamView team = pendingSelection < teams.size() ? teams.get(pendingSelection) : null;
                if (team == null || !team.selectable()) {
                    playUiSound(1.0F, 0.45F);
                    message = getText("unavailableMessage", "Эта территория сейчас недоступна. Выберите другую.");
                    pendingSelection = -1;
                    return true;
                }

                playUiSound(0.9F, 0.9F);
                submittingSelection = true;
                int teamId = team.id();
                pendingSelection = -1;
                ClientPlayNetworking.send(new SpawnPayloads.Choose(teamId));
                return true;
            }

            // Clicks outside the modal don't cancel or confirm; this avoids accidental choices.
            return true;
        }

        if (event.button() != 0) return super.mouseClicked(event, doubleClick);

        for (int i = 0; i < cardCount; i++) {
            int x = cardsX + i * (cardWidth + cardGap);
            if (!inside(mouseX, mouseY, x, cardsY, cardWidth, cardHeight)) continue;
            SpawnTeamView team = this.teams.get(i);
            if (!team.selectable()) {
                playUiSound(1.0F, 0.45F);
                this.message = getText("unavailableMessage", "Эта территория сейчас недоступна. Выберите другую.");
                return true;
            }
            playUiSound(0.9F, 0.7F);
            this.message = "";
            this.pendingSelection = i;
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
