package dev.para.spawnselector;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class SpawnSelectorMenu extends AbstractContainerMenu {
    private final Container container;

    public SpawnSelectorMenu(int id, Inventory playerInventory, Player player) {
        super(MenuType.GENERIC_9x3, id);
        this.container = new SimpleContainer(27);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                this.addSlot(new SpawnSlot(container, index, 8 + col * 18, 18 + row * 18));
            }
        }
        setupItems(player);
    }

    private void setupItems(Player player) {
        ItemStack filler = new ItemStack(Items.STAINED_GLASS_PANE.black());
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 0; i < 27; i++) container.setItem(i, filler.copy());

        int[] teamSlots = {10, 13, 16};
        for (int i = 0; i < 3 && i < SpawnConfig.spawnPoints.size(); i++) {
            SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(i);
            ItemStack icon = new ItemStack(i == 0 ? Items.WOOL.green() : i == 1 ? Items.WOOL.blue() : Items.WOOL.yellow());
            icon.set(DataComponents.CUSTOM_NAME, Component.literal("§l" + spawn.name));
            icon.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                    Component.literal(spawn.description),
                    Component.literal("§7Игроков: " + getTeamCount(player, spawn.team)),
                    Component.literal("§eНажмите, чтобы выбрать")
            )));
            container.setItem(teamSlots[i], icon);
        }

        ItemStack skip = new ItemStack(Items.BARRIER);
        skip.set(DataComponents.CUSTOM_NAME, Component.literal("§fНачать без команды"));
        skip.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                Component.literal("§7Вы не попадёте ни в одну из трёх команд."),
                Component.literal("§7Выбор закрепится, но команда не назначится."),
                Component.literal("§eНажмите, чтобы подтвердить отказ")
        )));
        container.setItem(22, skip);

        ItemStack title = new ItemStack(Items.BOOK);
        title.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lВыберите сторону"));
        title.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                Component.literal("§7Наведите курсор, чтобы прочитать описание."),
                Component.literal("§7Баланс команд защищает от сильного перевеса.")
        )));
        container.setItem(4, title);
    }

    private static int getTeamCount(Player player, String teamName) {
        if (player instanceof ServerPlayer serverPlayer) {
            var server = ((ServerLevel) serverPlayer.level()).getServer();
            var team = server.getScoreboard().getPlayerTeam(teamName);
            return team == null ? 0 : team.getPlayers().size();
        }
        return 0;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) {
        return player instanceof ServerPlayer && !SpawnData.hasSelectedSpawn((ServerPlayer) player);
    }
    @Override public void removed(Player player) {
        // Deliberately do not reopen from removed(): closing the menu must not create a reopen loop.
        super.removed(player);
    }

    private static class SpawnSlot extends Slot {
        private static final int[] TEAM_SLOTS = {10, 13, 16};
        SpawnSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) {
            if (!(player instanceof ServerPlayer serverPlayer)) return false;
            int index = -1;
            for (int i = 0; i < TEAM_SLOTS.length; i++) if (TEAM_SLOTS[i] == this.index) index = i;
            if (this.index == 22) index = 3;
            if (index >= 0) {
                int choice = index;
                ((ServerLevel) serverPlayer.level()).getServer().execute(() -> SpawnMenuHandler.handleSpawnSelection(serverPlayer, choice));
            }
            return false;
        }
    }
}
