package dev.para.spawnselector.client;

import dev.para.spawnselector.client.SpawnSelectorClient.SpawnTeamView;
import dev.para.spawnselector.network.SpawnPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

public class SpawnSelectorScreen extends Screen {
    private final List<SpawnTeamView> teams;
    private String message;
    private int hovered = -1;
    private boolean freeHovered;
    private boolean sending;
    private int panelX, panelY, panelW, panelH;

    public SpawnSelectorScreen(List<SpawnTeamView> teams, String message) {
        super(Component.literal("Выбор места появления"));
        this.teams = teams;
        this.message = message == null ? "" : message;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, 0xB9000000);
        panelW = Math.max(280, Math.min(760, width - 24));
        panelH = Math.max(250, Math.min(440, height - 20));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        g.fill(panelX - 3, panelY - 3, panelX + panelW + 3, panelY + panelH + 3, 0xFF111111);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF373737);
        g.fill(panelX + 4, panelY + 4, panelX + panelW - 4, panelY + panelH - 4, 0xFF242424);
        g.fill(panelX + 8, panelY + 8, panelX + panelW - 8, panelY + 34, 0xFF4C3825);
        drawCentered(g, "ВЫБОР МЕСТА ПОЯВЛЕНИЯ", width / 2, panelY + 16, 0xFFFFD46A);
        drawCentered(g, "Выберите команду или случайное место", width / 2, panelY + 43, 0xFFE0E0E0);

        int leftX = panelX + 14;
        int leftW = Math.min(246, panelW / 2 - 22);
        int cardY = panelY + 66;
        int cardH = Math.min(61, Math.max(46, (panelH - 168) / 3));
        hovered = -1;
        for (int i = 0; i < Math.min(3, teams.size()); i++) {
            int y = cardY + i * (cardH + 7);
            boolean over = inside(mouseX, mouseY, leftX, y, leftW, cardH);
            if (over) hovered = i;
            g.fill(leftX - 2, y - 2, leftX + leftW + 2, y + cardH + 2, over ? 0xFFFFD46A : 0xFF777777);
            g.fill(leftX, y, leftX + leftW, y + cardH, over ? 0xFF51452E : 0xFF303030);
            g.fill(leftX + 5, y + 5, leftX + 11, y + cardH - 5, teamColor(teams.get(i).color()));
            g.text(font, clip(teams.get(i).name(), leftW - 30), leftX + 18, y + 8, 0xFFFFFFFF, true);
            g.text(font, teams.get(i).countText(), leftX + 18, y + 24, 0xFFBEBEBE, true);
            g.text(font, "Выбрать", leftX + 18, y + cardH - 15, 0xFFFFD46A, true);
        }

        int freeY = cardY + 3 * (cardH + 7) + 2;
        boolean freeOver = inside(mouseX, mouseY, leftX, freeY, leftW, 40);
        freeHovered = freeOver;
        g.fill(leftX - 2, freeY - 2, leftX + leftW + 2, freeY + 42, freeOver ? 0xFFFFD46A : 0xFF777777);
        g.fill(leftX, freeY, leftX + leftW, freeY + 40, freeOver ? 0xFF315043 : 0xFF303030);
        drawCentered(g, "Свободное выживание", leftX + leftW / 2, freeY + 7, 0xFFFFFFFF);
        drawCentered(g, "Случайный выбор спавна", leftX + leftW / 2, freeY + 22, 0xFFBFE9CB);

        int previewX = leftX + leftW + 16;
        int previewW = Math.max(80, panelX + panelW - 14 - previewX);
        int previewY = panelY + 66;
        int previewH = Math.min(220, Math.max(100, panelH - 170));
        g.fill(previewX - 2, previewY - 2, previewX + previewW + 2, previewY + previewH + 2, 0xFF777777);
        drawPlaceholderLandscape(g, previewX, previewY, previewW, previewH, freeHovered ? 3 : hovered);
        if (freeHovered) {
            drawPreviewImage(g, "free_survival.png", previewX, previewY, previewW, previewH);
            int textY = previewY + previewH + 10;
            g.text(font, "Свободное выживание", previewX, textY, 0xFFFFD46A, true);
            drawWrapped(g, "Случайное место появления вдали от команд. Начните выживание самостоятельно и стройте базу где захотите.", previewX, textY + 16, previewW, 0xFFE0E0E0, panelY + panelH - 12);
        } else if (hovered >= 0 && hovered < teams.size()) {
            SpawnTeamView team = teams.get(hovered);
            drawPreviewImage(g, team.image(), previewX, previewY, previewW, previewH);
            int textY = previewY + previewH + 10;
            g.text(font, clip(team.name(), previewW), previewX, textY, 0xFFFFD46A, true);
            drawWrapped(g, team.description(), previewX, textY + 16, previewW, 0xFFE0E0E0, panelY + panelH - 12);
            g.text(font, team.countText(), previewX, panelY + panelH - 27, 0xFFBEBEBE, true);
        } else {
            drawCentered(g, "Выберите вариант", previewX + previewW / 2, previewY + previewH / 2, 0xFFFFFFFF);
            drawCentered(g, "чтобы увидеть описание", previewX + previewW / 2, previewY + previewH / 2 + 12, 0xFFCCCCCC);
        }
        if (!message.isBlank()) drawCentered(g, clip(message, panelW - 20), width / 2, panelY + panelH - 15, 0xFFFF7777);
        else if (sending) drawCentered(g, "Загрузка…", width / 2, panelY + panelH - 15, 0xFFFFD46A);
    }

    private void drawPreviewImage(GuiGraphicsExtractor g, String image, int x, int y, int w, int h) {
        if (image == null || image.isBlank()) return;
        String path = image.startsWith("textures/") ? image : "textures/gui/spawn_selector/" + image;
        Identifier id = Identifier.fromNamespaceAndPath("spawnselector", path);
        try {
            if (net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(id).isPresent()) {
                g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, w, h, 640, 360);
            }
        } catch (RuntimeException ignored) {
            // The built-in pixel-art placeholder remains visible when an optional image is absent.
        }
    }

    private void drawPlaceholderLandscape(GuiGraphicsExtractor g, int x, int y, int w, int h, int variant) {
        int sky = variant == 2 ? 0xFF6C8CA3 : variant == 1 ? 0xFF87B8D8 : 0xFF78A9D0;
        g.fill(x, y, x + w, y + h, sky);
        g.fill(x + w / 12, y + h / 5, x + w / 12 + Math.max(8, w / 6), y + h / 5 + Math.max(4, h / 18), 0xFFE4E9E5);
        g.fill(x + w / 2, y + h / 7, x + w / 2 + Math.max(8, w / 5), y + h / 7 + Math.max(4, h / 18), 0xFFE4E9E5);
        if (variant == 2) {
            g.fill(x, y + h / 2, x + w / 3, y + h, 0xFF536D7B);
            g.fill(x + w / 5, y + h / 3, x + w / 2, y + h, 0xFF6F8997);
            g.fill(x + w / 2, y + h / 2, x + w, y + h, 0xFF425E6A);
            g.fill(x + w / 3, y + h * 3 / 4, x + w * 2 / 3, y + h, 0xFF557B43);
        } else {
            g.fill(x, y + h * 2 / 3, x + w, y + h, variant == 1 ? 0xFF8BA85C : 0xFF527A3D);
            g.fill(x + w / 5, y + h / 2, x + w / 2, y + h * 3 / 4, variant == 1 ? 0xFF9EB96C : 0xFF426D34);
            g.fill(x + w * 2 / 3, y + h / 2, x + w * 5 / 6, y + h * 3 / 4, 0xFF426D34);
            if (variant == 1) {
                g.fill(x + w / 3, y + h * 2 / 3, x + w / 2, y + h * 5 / 6, 0xFF9C7652);
                g.fill(x + w / 3, y + h / 2, x + w / 2, y + h * 2 / 3, 0xFFD5C29A);
            }
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
        int leftX = panelX + 14;
        int leftW = Math.min(246, panelW / 2 - 22);
        int cardY = panelY + 66;
        int cardH = Math.min(61, Math.max(46, (panelH - 168) / 3));
        for (int i = 0; i < Math.min(3, teams.size()); i++) {
            int y = cardY + i * (cardH + 7);
            if (inside(mouseX, mouseY, leftX, y, leftW, cardH)) { sendChoice(i); return true; }
        }
        int freeY = cardY + 3 * (cardH + 7) + 2;
        if (inside(mouseX, mouseY, leftX, freeY, leftW, 40)) { sendChoice(3); return true; }
        return super.mouseClicked(event, doubleClick);
    }
    private void sendChoice(int index) {
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