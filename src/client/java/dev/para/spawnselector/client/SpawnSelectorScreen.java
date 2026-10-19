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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

public class SpawnSelectorScreen extends Screen {
    private final List<SpawnTeamView> teams;
    private final Map<String, String> menuText;
    private boolean compactLayout;
    private final String message;
    private int hovered = -1;
    private int lastHovered = -1;
    private static final Map<String, Boolean> ICON_VALIDITY = new ConcurrentHashMap<>();

    // Уникальная палитра фракций:
    // 0: Европа — Сапфировый / Лазурный
    // 1: Азия — Рубиновый / Карминовый
    // 2: Австралия — Янтарно-золотой
    private static final int[] THEME_COLORS = { 0xFF3B82F6, 0xFFEF4444, 0xFFF59E0B };
    private static final int[] THEME_DARK =   { 0xFF1E3A8A, 0xFF7F1D1D, 0xFF78350F };
    private static final int[] THEME_LIGHT =  { 0xFF93C5FD, 0xFFFCA5A5, 0xFFFDE68A };

    public SpawnSelectorScreen(List<SpawnTeamView> teams, int unassignedOnlineCount, String message, Map<String, String> menuText) {
        super(Component.literal(menuText.getOrDefault("screenTitle", "ВЫБОР ТЕРРИТОРИИ")));
        this.teams = teams;
        this.message = message;
        this.menuText = menuText;
    }

    @Override
    protected void init() {
        super.init();
        this.compactLayout = this.height < 340;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void renderBackground(Object graphics, int mouseX, int mouseY, float delta) {
        // Элегантное глубокое затемнение игрового мира
        GuiGraphicsExtractor.fill(graphics, 0, 0, this.width, this.height, 0xEE080B12);
    }

    @Override
    public void render(Object graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        this.hovered = -1;

        // Заголовок экрана
        int titleY = compactLayout ? 10 : 20;
        String titleText = getText("screenTitle", "ВЫБОР ТЕРРИТОРИИ");
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§l" + titleText, this.width / 2, titleY, 0xFFFFFF);

        String subtitle = getText("screenSubtitle", "Выберите стартовую фракцию для основания своей базы");
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§7" + subtitle, this.width / 2, titleY + 13, 0x94A3B8);

        if (this.message != null && !this.message.isEmpty()) {
            GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§c⚠ " + this.message, this.width / 2, titleY + 26, 0xF87171);
        }

        // 3 вертикальные hero-панели территорий
        int count = Math.min(this.teams.size(), 3);
        int cardW = compactLayout ? 144 : 168;
        int cardH = compactLayout ? 186 : 228;
        int cardGap = compactLayout ? 12 : 18;

        // Авто-масштабирование, если экран уже ширины карточек
        int maxAvailableW = this.width - 32;
        if ((count * cardW) + ((count - 1) * cardGap) > maxAvailableW && count > 0) {
            cardW = (maxAvailableW - ((count - 1) * cardGap)) / count;
        }

        int totalWidth = (count * cardW) + ((count - 1) * cardGap);
        int startX = (this.width - totalWidth) / 2;

        int headerSpace = titleY + 34;
        int startY = headerSpace + Math.max(0, (this.height - headerSpace - cardH) / 2);

        for (int i = 0; i < count; i++) {
            int cx = startX + i * (cardW + cardGap);
            int cy = startY;
            boolean isHovered = mouseX >= cx && mouseX < cx + cardW && mouseY >= cy && mouseY < cy + cardH;
            if (isHovered) this.hovered = i;
            renderTerritoryCard(graphics, cx, cy, cardW, cardH, this.teams.get(i), i, isHovered, mouseX, mouseY);
        }

        // Звуковой отклик при наведении на новую панель
        if (this.hovered != this.lastHovered && this.hovered != -1) {
            playHoverSound();
        }
        this.lastHovered = this.hovered;
    }

    private void renderTerritoryCard(Object graphics, int x, int y, int w, int h, SpawnTeamView team, int index, boolean isHovered, int mouseX, int mouseY) {
        int themeColor = THEME_COLORS[index % THEME_COLORS.length];
        int themeDark  = THEME_DARK[index % THEME_DARK.length];
        int themeLight = THEME_LIGHT[index % THEME_LIGHT.length];

        // Фон панели и контурная подсветка
        int bg = isHovered ? 0xF5131C2C : 0xDF0C1320;
        int border = isHovered ? themeColor : (team.selectable() ? 0xFF28354A : 0xFF3D2323);

        GuiGraphicsExtractor.fill(graphics, x, y, x + w, y + h, bg);
        drawOutline(graphics, x, y, w, h, border);

        // Верхняя акцентная линия фракции
        GuiGraphicsExtractor.fill(graphics, x + 1, y + 1, x + w - 1, y + 3, isHovered ? themeLight : themeColor);

        int pad = compactLayout ? 7 : 9;
        int contentX = x + pad;
        int contentW = w - pad * 2;
        int curY = y + pad + 2;

        // 1. Арт / Превью территории
        int iconH = compactLayout ? 50 : 66;
        renderTerritoryArt(graphics, team.buttonIcon(), contentX, curY, contentW, iconH, isHovered, themeColor);
        curY += iconH + (compactLayout ? 6 : 8);

        // 2. Название территории (Европа / Азия / Австралия)
        int nameColor = team.selectable() ? (isHovered ? themeLight : 0xFFFFFF) : 0x777777;
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§l" + team.name(), x + w / 2, curY, nameColor);
        curY += (compactLayout ? 12 : 15);

        // 3. Бейдж онлайна: [ ● X онлайн | Y всего ]
        int pillH = compactLayout ? 13 : 15;
        GuiGraphicsExtractor.fill(graphics, contentX + 6, curY, contentX + contentW - 6, curY + pillH, 0x55000000);
        drawOutline(graphics, contentX + 6, curY, contentW - 12, pillH, 0x22FFFFFF);

        String statsText = String.format("§a● %d §7онлайн §8| §f%d §7всего", team.onlineCount(), team.totalCount());
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, statsText, x + w / 2, curY + (compactLayout ? 2 : 3), 0xDDDDDD);
        curY += pillH + (compactLayout ? 6 : 8);

        // 4. Описание территории
        int btnH = compactLayout ? 20 : 24;
        int btnY = y + h - pad - btnH;
        int descMaxH = Math.max(10, btnY - curY - 4);
        drawMultilineText(graphics, team.description(), contentX, curY, contentW, descMaxH, 0xCBD5E1);

        // 5. Большая кнопка выбора фракции
        boolean btnHover = isHovered && mouseX >= contentX && mouseX < contentX + contentW && mouseY >= btnY && mouseY < btnY + btnH;
        int btnBg = !team.selectable() ? 0x44261717 : (btnHover ? themeColor : themeDark);
        int btnBorder = !team.selectable() ? 0xFF4A2222 : (btnHover ? 0xFFFFFFFF : themeColor);

        GuiGraphicsExtractor.fill(graphics, contentX, btnY, contentX + contentW, btnY + btnH, btnBg);
        drawOutline(graphics, contentX, btnY, contentW, btnH, btnBorder);

        String btnText = team.selectable()
                ? getText("selectTeamButton", "ВЫБРАТЬ")
                : getText("teamFullText", "ЗАПОЛНЕНА");
        int btnTextColor = team.selectable() ? 0xFFFFFF : 0x888888;
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§l" + btnText, x + w / 2, btnY + (btnH - 8) / 2, btnTextColor);
    }

    private void renderTerritoryArt(Object graphics, String iconPath, int x, int y, int w, int h, boolean isHovered, int accentColor) {
        if (iconPath != null && !iconPath.trim().isEmpty()) {
            try {
                String cleanPath = iconPath.trim();
                Identifier id = cleanPath.contains(":")
                        ? Identifier.parse(cleanPath)
                        : Identifier.fromNamespaceAndPath("spawnselector", cleanPath);

                Boolean valid = ICON_VALIDITY.get(cleanPath);
                if (valid == null) {
                    try (var stream = Minecraft.getInstance().getResourceManager().getResource(id).orElseThrow().open()) {
                        valid = ImageIO.read(stream) != null;
                    } catch (Exception e) {
                        valid = false;
                    }
                    ICON_VALIDITY.put(cleanPath, valid);
                }

                if (valid) {
                    RenderPipelines.COLOR_TEXTURE.get();
                    GuiGraphicsExtractor.blit(graphics, id, x, y, 0.0f, 0.0f, w, h, w, h);
                    drawOutline(graphics, x, y, w, h, isHovered ? accentColor : 0xFF2A374F);
                    return;
                }
            } catch (Exception ignored) {}
        }

        // Стилизованная подложка, если изображение грузится
        int placeholderBg = isHovered ? 0xFF1C2738 : 0xFF121B27;
        GuiGraphicsExtractor.fill(graphics, x, y, x + w, y + h, placeholderBg);
        drawOutline(graphics, x, y, w, h, isHovered ? accentColor : 0xFF253347);
        GuiGraphicsExtractor.drawCenteredString(graphics, this.font, "§8[ ТЕРРИТОРИЯ ]", x + w / 2, y + h / 2 - 4, accentColor);
    }

    private void drawMultilineText(Object graphics, String text, int x, int y, int maxW, int maxH, int color) {
        if (text == null || text.isEmpty() || maxH < 8) return;
        List<String> words = List.of(text.split(" "));
        StringBuilder line = new StringBuilder();
        int curY = y;
        int lineHeight = compactLayout ? 9 : 10;

        for (String word : words) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (this.font.width(test) <= maxW) {
                line = new StringBuilder(test);
            } else {
                if (curY + lineHeight > y + maxH) break;
                GuiGraphicsExtractor.drawString(graphics, this.font, line.toString(), x, curY, color, false);
                line = new StringBuilder(word);
                curY += lineHeight;
            }
        }
        if (line.length() > 0 && curY + lineHeight <= y + maxH) {
            GuiGraphicsExtractor.drawString(graphics, this.font, line.toString(), x, curY, color, false);
        }
    }

    private String getText(String key, String fallback) {
        if (this.menuText != null && this.menuText.containsKey(key)) {
            String val = this.menuText.get(key);
            if (val != null && !val.isBlank()) return val;
        }
        return fallback;
    }

    private void drawOutline(Object graphics, int x, int y, int w, int h, int color) {
        GuiGraphicsExtractor.fill(graphics, x, y, x + w, y + 1, color);
        GuiGraphicsExtractor.fill(graphics, x, y + h - 1, x + w, y + h, color);
        GuiGraphicsExtractor.fill(graphics, x, y + 1, y + h, color);
        GuiGraphicsExtractor.fill(graphics, x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            // Клик по любой из трёх карточек территорий
            if (this.hovered >= 0 && this.hovered < this.teams.size()) {
                SpawnTeamView team = this.teams.get(this.hovered);
                if (team.selectable()) {
                    playClickSound();
                    ClientPlayNetworking.send(new SpawnPayloads.SelectSpawn(team.id()));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void playClickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    private void playHoverSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.35F, 1.6F));
    }
}
