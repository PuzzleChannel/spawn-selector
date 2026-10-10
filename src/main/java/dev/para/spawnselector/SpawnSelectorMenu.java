package dev.para.spawnselector;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;

public class SpawnSelectorMenu extends AbstractContainerMenu {

    private final Container container;

    public SpawnSelectorMenu(int id, Inventory playerInventory, Player player) {
        super(MenuType.GENERIC_9x3, id);
        this.container = new SimpleContainer(27);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                this.addSlot(new SpawnSlot(container, index, 8 + col * 18, 18 + row * 18, player));
            }
        }

        setupSpawnItems();
    }

    private void setupSpawnItems() {
        ItemStack glassPane = new ItemStack(Items.STAINED_GLASS_PANE.black());
        glassPane.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(" "));

        for (int i = 0; i < 27; i++) {
            container.setItem(i, glassPane.copy());
        }

        ItemStack[] spawnItems = {
            new ItemStack(Items.WOOL.red()),
            new ItemStack(Items.WOOL.blue()),
            new ItemStack(Items.WOOL.green()),
            new ItemStack(Items.WOOL.yellow()),
            new ItemStack(Items.WOOL.purple())
        };

        int[] spawnSlots = {9, 11, 13, 15, 17};

        for (int i = 0; i < Math.min(5, SpawnConfig.spawnPoints.size()); i++) {
            if (i < spawnSlots.length) {
                SpawnConfig.SpawnPoint spawn = SpawnConfig.spawnPoints.get(i);
                ItemStack stack = spawnItems[i];
                stack.set(DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal(spawn.name));
                container.setItem(spawnSlots[i], stack);
            }
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        if (!SpawnData.hasSelectedSpawn((ServerPlayer) player)) {
            player.level().getServer().execute(() -> {
                ((ServerPlayer) player).openMenu(new net.minecraft.world.SimpleMenuProvider(
                    (id, inventory, p) -> new SpawnSelectorMenu(id, inventory, p),
                    net.minecraft.network.chat.Component.literal("Выбор спавна")
                ));
            });
        }
    }

    private static class SpawnSlot extends Slot {
        private final Player menuPlayer;
        private static final int[] SPAWN_SLOTS = {9, 11, 13, 15, 17};

        public SpawnSlot(Container container, int slot, int x, int y, Player player) {
            super(container, slot, x, y);
            this.menuPlayer = player;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            int spawnIndex = getSpawnIndex(this.index);
            if (spawnIndex >= 0 && player instanceof ServerPlayer serverPlayer) {
                if (spawnIndex < SpawnConfig.spawnPoints.size()) {
                    SpawnSelectorMod.LOGGER.info("Клик по слоту {}, индекс спавна: {}", this.index, spawnIndex);

                    serverPlayer.level().getServer().execute(() -> {
                        SpawnMenuHandler.handleSpawnSelection(serverPlayer, spawnIndex);
                    });
                }
            }
            return false;
        }

        private int getSpawnIndex(int slotIndex) {
            for (int i = 0; i < SPAWN_SLOTS.length; i++) {
                if (SPAWN_SLOTS[i] == slotIndex) {
                    return i;
                }
            }
            return -1;
        }
    }
}
