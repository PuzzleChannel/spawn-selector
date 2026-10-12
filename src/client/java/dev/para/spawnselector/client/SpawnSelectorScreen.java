package dev.para.spawnselector.client;

import dev.para.spawnselector.client.SpawnSelectorClient.SpawnTeamView;
import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

public class SpawnSelectorScreen extends Screen {
    private List<SpawnTeamView> teams;
    private final Map<String, String> menuText;
    private boolean compactLayout;
    private String message;
    private int hovered = -1;
    private boolean freeHovered;
    private int stickyPreviewChoice = -1;
    private boolean sending;
    private int confirmingChoice = -1;
    private String lastHoverTarget = "";
    private int panelX, panelY, panelW, panelH;
    private static final Map<Identifier, int[]> IMAGE_SIZES = new ConcurrentHashMap<>();

    public SpawnSelectorScreen(List<SpawnTeamView> teams, String message, Map<String, String> menuText) {
        super(Component.literal(text(menuText, "windowTitle", "Выбор территории")));
        this.teams = teams;
        this.message = message == null ? "" : message;
        this.menuText = menuText == null ? Map.of() : Map.copyOf(menuText);
    }

    private static String text(Map<String, String> values, String key, String fallback) {
        String value = values == null ? null : values.get(key);
        return value == null ? fallback : value;
    }
    private String t(String key, String fallback) { return text(menuText, key, fallback); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, 0xB9000000);
        String hoverTarget = "";
        panelW = Math.max(1, Math.min(760, width - 16));
        panelH = Math.max(1, Math.min(440, height - 16));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        compactLayout = width < 600 || height < 400;
        g.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2, 0xFF111111);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, color("stylePanel", 0xFF373737));
        if (panelW > 8 && panelH > 8) g.fill(panelX + 4, panelY + 4, panelX + panelW - 4, panelY + panelH - 4, 0xFF242424);
        g.fill(panelX + 6, panelY + 6, panelX + panelW - 6, panelY + (compactLayout ? 28 : 34), 0xFF4C3825);
        drawCentered(g, t("headerTitle", "ВЫБОР ТЕРРИТОРИИ"), width / 2, panelY + (compactLayout ? 9 : 16), color("styleAccent", 0xFFFFD46A));
        if (!compactLayout) drawCentered(g, t("headerSubtitle", "Выберите территорию или свободное выживание"), width / 2, panelY + 43, 0xFFE0E0E0);

        int leftX = panelX + (compactLayout ? 10 : 14);
        int leftW = compactLayout ? Math.max(1, panelW - 20) : Math.max(100, Math.min(246, panelW / 2 - 22));
        int cardY = panelY + (compactLayout ? 38 : 66);
        int cardH = compactLayout
                ? Math.max(22, Math.min(50, (panelH - 88) / 4 - 4))
                : Math.max(38, Math.min(61, (panelH - 168) / 3));
        int gap = compactLayout ? 4 : 7;
        hovered = -1;
        for (int i = 0; i < Math.min(3, teams.size()); i++) {
            int y = cardY + i * (cardH + gap);
            boolean unavailable = !teams.get(i).available();
            boolean over = confirmingChoice < 0 && !unavailable && inside(mouseX, mouseY, leftX, y, leftW, cardH);
            if (over) {
                hovered = i;
                stickyPreviewChoice = i;
                hoverTarget = "territory_" + i;
            }
            g.fill(leftX - 1, y - 1, leftX + leftW + 1, y + cardH + 1, over ? color("styleAccent", 0xFFFFD46A) : unavailable ? 0xFF663B3B : 0xFF777777);
            g.fill(leftX, y, leftX + leftW, y + cardH, unavailable ? 0xFF292323 : over ? 0xFF51452E : color("styleCard", 0xFF303030));
            if (cardH >= 20) g.fill(leftX + 4, y + 4, leftX + 8, y + cardH - 4, teamColor(teams.get(i).color()));
            int textW = leftW - 28;
            if (cardH >= 38) {
                int thumbW = Math.min(92, Math.max(1, leftW / 3));
                int thumbH = Math.max(1, cardH - 8);
                int thumbX = leftX + leftW - thumbW - 4;
                drawPreviewImage(g, teams.get(i).image(), thumbX, y + 4, thumbW, thumbH);
                if (unavailable) g.fill(thumbX, y + 4, thumbX + thumbW, y + 4 + thumbH, 0x99000000);
                textW = Math.max(1, thumbX - (leftX + 13) - 5);
            }
            g.text(font, clip(teams.get(i).name(), textW), leftX + 13, y + (compactLayout ? 5 : 8), 0xFFFFFFFF, true);
            if (!compactLayout || cardH >= 38) g.text(font, clip(teams.get(i).countText(), textW), leftX + 13, y + 22, 0xFFBEBEBE, true);
            if (!compactLayout) g.text(font, clip(unavailable ? t("unavailable", "Недоступно") : t("chooseButton", "Выбрать"), textW), leftX + 18, y + cardH - 15, unavailable ? 0xFFFF8888 : color("styleAccent", 0xFFFFD46A), true);
        }

        int freeY = cardY + 3 * (cardH + gap) + (compactLayout ? 0 : 2);
        int freeH = compactLayout ? cardH : 40;
        boolean freeOver = confirmingChoice < 0 && inside(mouseX, mouseY, leftX, freeY, leftW, freeH);
        freeHovered = freeOver;
        if (freeOver) hoverTarget = "free_survival";
        g.fill(leftX - 1, freeY - 1, leftX + leftW + 1, freeY + freeH + 1, freeOver ? 0xFFFFD46A : 0xFF777777);
        g.fill(leftX, freeY, leftX + leftW, freeY + freeH, freeOver ? 0xFF315043 : 0xFF303030);
        if (compactLayout) {
            drawCentered(g, clip(t("freeName", "Свободное выживание"), leftW - 12), leftX + leftW / 2, freeY + Math.max(5, (freeH - 9) / 2), 0xFFFFFFFF);
        } else {
            int freeThumbW = Math.min(68, Math.max(1, leftW / 3));
            int freeThumbH = Math.max(1, freeH - 8);
            int freeThumbX = leftX + leftW - freeThumbW - 4;
            drawPreviewImage(g, "free_survival.png", freeThumbX, freeY + 4, freeThumbW, freeThumbH);
            int freeTextCenter = leftX + (leftW - freeThumbW - 8) / 2;
            drawCentered(g, clip(t("freeName", "Свободное выживание"), leftW - freeThumbW - 18), freeTextCenter, freeY + 7, 0xFFFFFFFF);
            drawCentered(g, clip(t("freeSubtitle", "Случайный выбор спавна"), leftW - freeThumbW - 18), freeTextCenter, freeY + 22, 0xFFBFE9CB);
        }

        if (!compactLayout) {
            int previewX = leftX + leftW + 16;
            int previewW = Math.max(1, panelX + panelW - 14 - previewX);
            int previewY = panelY + 66;
            int previewH = Math.max(60, Math.min(220, panelH - 170));
            g.fill(previewX - 2, previewY - 2, previewX + previewW + 2, previewY + previewH + 2, 0xFF777777);
            g.fill(previewX, previewY, previewX + previewW, previewY + previewH, 0xFF000000);
            if (freeHovered) {
                drawPreviewImage(g, "free_survival.png", previewX, previewY, previewW, previewH);
                int textY = previewY + previewH + 10;
                g.text(font, clip(t("freeName", "Свободное выживание"), previewW), previewX, textY, 0xFFFFD46A, true);
                drawWrapped(g, t("freeDescription", "Случайное место появления вдали от команд. Начните выживание самостоятельно и стройте базу где захотите."), previewX, textY + 16, previewW, 0xFFE0E0E0, panelY + panelH - 12);
            } else if ((hovered >= 0 && hovered < teams.size()) || (stickyPreviewChoice >= 0 && stickyPreviewChoice < teams.size())) {
                int previewChoice = hovered >= 0 && hovered < teams.size() ? hovered : stickyPreviewChoice;
                SpawnTeamView team = teams.get(previewChoice);
                drawPreviewImage(g, team.image(), previewX, previewY, previewW, previewH);
                int textY = previewY + previewH + 10;
                g.text(font, clip(team.name(), previewW), previewX, textY, 0xFFFFD46A, true);
                drawWrapped(g, team.description(), previewX, textY + 16, previewW, 0xFFE0E0E0, panelY + panelH - 12);
                g.text(font, team.countText(), previewX, panelY + panelH - 27, 0xFFBEBEBE, true);
            }
        }
        if (!message.isBlank()) drawCentered(g, clip(message, panelW - 20), width / 2, panelY + panelH - 15, 0xFFFF7777);
        else if (sending) drawCentered(g, t("loading", "Загрузка…"), width / 2, panelY + panelH - 15, 0xFFFFD46A);
        if (confirmingChoice >= 0) {
            int confirmationHover = hoveredConfirmationButton(mouseX, mouseY);
            hoverTarget = confirmationHover == 0 ? "confirm" : confirmationHover == 1 ? "back" : "";
            drawConfirmation(g, mouseX, mouseY);
        }
        updateHoverSound(hoverTarget);
    }

    private void drawConfirmation(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int w = Math.max(1, Math.min(480, width - 16));
        int h = Math.max(140, Math.min(190, height - 16));
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        g.fill(0, 0, width, height, 0xD9000000);
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF111111);
        g.fill(x, y, x + w, y + h, color("stylePanel", 0xFF373737));
        g.fill(x + 4, y + 4, x + w - 4, y + h - 4, 0xFF242424);
        String name = confirmingChoice == 3 ? t("freeName", "Свободное выживание") : teams.get(confirmingChoice).name();
        String description = confirmingChoice == 3
                ? t("confirmFreeDescription", "Вы появитесь в случайном месте вдали от территорий. Выбор нельзя будет изменить самостоятельно.")
                : teams.get(confirmingChoice).description();
        drawCentered(g, t("confirmTitle", "ПОДТВЕРЖДЕНИЕ ВЫБОРА"), width / 2, y + 14, 0xFFFFD46A);
        drawCentered(g, clip(name, w - 24), width / 2, y + 35, 0xFFFFFFFF);
        drawWrapped(g, description, x + 12, y + 54, w - 24, 0xFFE0E0E0, y + h - 48);
        int buttonY = y + h - 36;
        int buttonW = Math.max(1, (w - 36) / 2);
        int hoveredButton = hoveredConfirmationButton(mouseX, mouseY);
        boolean confirmHovered = hoveredButton == 0;
        boolean backHovered = hoveredButton == 1;
        int accent = color("styleAccent", 0xFFFFD46A);

        // A bright frame and lighter fill make the hovered action obvious.
        g.fill(x + 10, buttonY - 2, x + 14 + buttonW, buttonY + 26,
                confirmHovered ? accent : 0xFF777777);
        g.fill(x + 12, buttonY, x + 12 + buttonW, buttonY + 24,
                confirmHovered ? 0xFF59815D : color("styleConfirmButton", 0xFF426D45));
        int backX = x + w - 12 - buttonW;
        g.fill(backX - 2, buttonY - 2, backX + buttonW + 2, buttonY + 26,
                backHovered ? accent : 0xFF777777);
        g.fill(backX, buttonY, backX + buttonW, buttonY + 24,
                backHovered ? 0xFF8B5555 : color("styleBackButton", 0xFF6B4141));

        drawCentered(g, clip(t("confirmButton", "Подтвердить"), buttonW - 8),
                x + 12 + buttonW / 2, buttonY + 8, 0xFFFFFFFF);
        drawCentered(g, clip(t("backButton", "Назад"), buttonW - 8),
                backX + buttonW / 2, buttonY + 8, 0xFFFFFFFF);
    }

    /** 0 = Confirm, 1 = Back, -1 = no button under the pointer. */
    private int hoveredConfirmationButton(double mouseX, double mouseY) {
        if (confirmingChoice < 0) return -1;
        int w = Math.max(1, Math.min(480, width - 16));
        int h = Math.max(140, Math.min(190, height - 16));
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        int buttonY = y + h - 36;
        int buttonW = Math.max(1, (w - 36) / 2);
        if (inside(mouseX, mouseY, x + 12, buttonY, buttonW, 24)) return 0;
        if (inside(mouseX, mouseY, x + w - 12 - buttonW, buttonY, buttonW, 24)) return 1;
        return -1;
    }

    private void updateHoverSound(String target) {
        if (!target.equals(lastHoverTarget) && !target.isEmpty()) {
            playUiSound(1.45F, 0.18F);
        }
        lastHoverTarget = target;
    }

    private void playUiSound(float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume));
    }

    private void drawPreviewImage(GuiGraphicsExtractor g, String image, int x, int y, int w, int h) {
        if (image == null || image.isBlank()) return;
        String path = image.startsWith("textures/") ? image : "textures/gui/spawn_selector/" + image;
        Identifier id = Identifier.fromNamespaceAndPath("spawnselector", path);
        try {
            if (net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(id).isPresent()) {
                int[] sourceSize = IMAGE_SIZES.computeIfAbsent(id, key -> {
                    try {
                        var resource = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(key);
                        if (resource.isPresent()) {
                            try (var stream = resource.get().open()) {
                                var data = ImageIO.read(stream);
                                if (data != null) return new int[]{data.getWidth(), data.getHeight()};
                            }
                        }
                    } catch (Exception ignored) { }
                    return new int[]{320, 180};
                });
                int sourceW = Math.max(1, sourceSize[0]);
                int sourceH = Math.max(1, sourceSize[1]);
                double scale = Math.min(w / (double) sourceW, h / (double) sourceH);
                int drawW = Math.max(1, (int) Math.round(sourceW * scale));
                int drawH = Math.max(1, (int) Math.round(sourceH * scale));
                int drawX = x + (w - drawW) / 2;
                int drawY = y + (h - drawH) / 2;
                g.fill(x, y, x + w, y + h, 0xFF000000);
                g.blit(RenderPipelines.GUI_TEXTURED, id, drawX, drawY, 0, 0, drawW, drawH, sourceW, sourceH, sourceW, sourceH);
            }
        } catch (RuntimeException ignored) {
            // Keep the preview background black if an optional image is missing.
        }
    }

    private void drawWrapped(GuiGraphicsExtractor g, String text, int x, int y, int maxWidth, int color, int bottom) {
        StringBuilder line = new StringBuilder();
        int lineY = y;
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) > maxWidth && !line.isEmpty()) {
                if (lineY + 10 > bottom) break;
                g.text(font, line.toString(), x, lineY, color, true);
                lineY += 12; line.setLength(0); line.append(word);
            } else {
                if (!line.isEmpty()) line.append(' ');
                line.append(word);
            }
        }
        if (!line.isEmpty() && lineY <= bottom) g.text(font, line.toString(), x, lineY, color, true);
    }

    private void drawCentered(GuiGraphicsExtractor g, String text, int centerX, int y, int color) {
        g.text(font, text, centerX - font.width(text) / 2, y, color, true);
    }

    private String clip(String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) return text;
        String out = text;
        while (!out.isEmpty() && font.width(out + "…") > maxWidth) out = out.substring(0, out.length() - 1);
        return out + "…";
    }
    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (event.button() != 0 || sending) return false;
        if (confirmingChoice >= 0) {
            int hoveredButton = hoveredConfirmationButton(mouseX, mouseY);
            if (hoveredButton == 0) {
                int choice = confirmingChoice;
                confirmingChoice = -1;
                sendChoice(choice);
            } else if (hoveredButton == 1) {
                playUiSound(1.0F, 0.45F);
                stickyPreviewChoice = confirmingChoice;
                confirmingChoice = -1;
                message = "";
                lastHoverTarget = "";
            }
            return true;
        }
        int leftX = panelX + (compactLayout ? 10 : 14);
        int leftW = compactLayout ? Math.max(1, panelW - 20) : Math.max(100, Math.min(246, panelW / 2 - 22));
        int cardY = panelY + (compactLayout ? 38 : 66);
        int cardH = compactLayout ? Math.max(22, Math.min(50, (panelH - 88) / 4 - 4)) : Math.max(38, Math.min(61, (panelH - 168) / 3));
        int gap = compactLayout ? 4 : 7;
        for (int i = 0; i < Math.min(3, teams.size()); i++) {
            int y = cardY + i * (cardH + gap);
            if (inside(mouseX, mouseY, leftX, y, leftW, cardH)) {
                if (!teams.get(i).available()) { message = t("unavailableMessage", "Эта территория сейчас недоступна. Выберите другую."); return true; }
                playUiSound(1.0F, 0.45F);
                confirmingChoice = i; stickyPreviewChoice = i; message = ""; lastHoverTarget = ""; return true;
            }
        }
        int freeY = cardY + 3 * (cardH + gap) + (compactLayout ? 0 : 2);
        int freeH = compactLayout ? cardH : 40;
        if (inside(mouseX, mouseY, leftX, freeY, leftW, freeH)) {
            playUiSound(1.0F, 0.45F);
            confirmingChoice = 3; message = ""; lastHoverTarget = ""; return true;
        }
        return super.mouseClicked(event, doubleClick);
    }
    public void updateTeams(JsonArray updates) {
        if (updates == null || teams == null) return;
        java.util.ArrayList<SpawnTeamView> refreshed = new java.util.ArrayList<>(teams);
        for (int i = 0; i < Math.min(refreshed.size(), updates.size()); i++) {
            if (!updates.get(i).isJsonObject()) continue;
            JsonObject update = updates.get(i).getAsJsonObject();
            SpawnTeamView old = refreshed.get(i);
            int count = update.has("count") ? update.get("count").getAsInt() : old.count();
            int max = update.has("max") ? update.get("max").getAsInt() : old.max();
            boolean available = !update.has("available") || update.get("available").getAsBoolean();
            String template = t(max > 0 ? "playersLimited" : "players", max > 0 ? "{count}/{max} игроков" : "{count} игроков");
            String countText = template.replace("{count}", Integer.toString(count)).replace("{max}", Integer.toString(max));
            refreshed.set(i, new SpawnTeamView(old.name(), old.description(), old.image(), count, max, old.color(), countText, available));
        }
        teams = refreshed;
        if (stickyPreviewChoice >= 0 && stickyPreviewChoice < teams.size() && !teams.get(stickyPreviewChoice).available()) stickyPreviewChoice = -1;
    }

    private int color(String key, int fallback) {
        String raw = t(key, "");
        if (raw == null || raw.isBlank()) return fallback;
        try {
            String hex = raw.startsWith("#") ? raw.substring(1) : raw;
            long parsed = Long.parseLong(hex, 16);
            if (hex.length() == 6) parsed |= 0xFF000000L;
            return (int) parsed;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    private void sendChoice(int index) {
        playUiSound(0.9F, 0.9F);
        sending = true; message = "";
        ClientPlayNetworking.send(new SpawnPayloads.Choose(index));
    }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void onClose() { /* The player must choose explicitly. */ }
    @Override public boolean isPauseScreen() { return false; }

    private static int teamColor(String color) {
        if (color == null) return 0xFF55AA55;
        return switch (color.toLowerCase(java.util.Locale.ROOT)) {
            case "red" -> 0xFFCC5555;
            case "blue" -> 0xFF5599DD;
            case "gold", "yellow" -> 0xFFE0BB55;
            case "green" -> 0xFF55AA55;
            default -> 0xFF999999;
        };
    }
}