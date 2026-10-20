package dev.para.spawnselector.mixin;

import dev.para.spawnselector.TerritoryBonuses;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateMixin {
    @Inject(method = "getDestroyProgress", at = @At("RETURN"), cancellable = true)
    private void spawnselector$applyCapitalMiningBonus(Player player, BlockGetter level, BlockPos pos,
                                                       CallbackInfoReturnable<Float> cir) {
        float multiplier = TerritoryBonuses.miningSpeedMultiplier(player, (BlockState) (Object) this);
        if (multiplier > 1.0F) {
            cir.setReturnValue(cir.getReturnValue() * multiplier);
        }
    }
}
