package com.habitrain.core.client.mixin;

import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.client.RoleClientExtensionApi;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import com.habitrain.core.role.extension.ManagedRoleEntry;
import com.habitrain.core.role.extension.RoleExtensionRegistry;
import io.wifi.starrailexpress.api.SRERole;
import io.wifi.starrailexpress.cca.SREGameWorldComponent;
import io.wifi.starrailexpress.client.SREClient;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin - 移除关灯模式警长的杀手透视（全图夜视 + 伽马提亮 + 穿墙看人）。
 *
 * 警长（isVigilanteTeam()=true）因 canUseKiller=true → setCanUseInstinct(true)，
 * 使 isInstinctEnabled() 返回 true，从而获得了本应只属于杀手的透视。
 * 此处仅对未显式授予本能的原版警长返回 false，关闭上述误得的杀手透视。
 * 仅当本地玩家存活（非旁观/非创造）时生效：死亡进入旁观/创造后放行，
 * 保留 SRE 自带的旁观透视（上游对旁观者恒开启）。
 * 不改动 canUseKiller/canUseInstinct，因此警长的商店/左轮/手铐权限不受影响。
 */
@Mixin(value = SREClient.class, remap = false)
public class InstinctSheriffGateMixin {

    @Inject(
            method = "isInstinctEnabled",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void habitrain$noSheriffInstinct(CallbackInfoReturnable<Boolean> cir) {
        SREGameWorldComponent gameComponent = SREClient.gameComponent;
        if (gameComponent == null) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        SRERole role = gameComponent.getRole(player);
        // 仅存活时移除警长误得的杀手透视；旁观/创造或由 v2 显式授予本能时放行。
        if (role != null && java.util.Objects.equals(role.identifier(),
                io.wifi.starrailexpress.api.TMMRoles.VIGILANTE.identifier()) && !SREClient.isPlayerSpectatingOrCreative()) {
            if (habitrain$hasExplicitInstinctGrant(role)) {
                return;
            }
            cir.setReturnValue(false);
        }
    }

    @Unique
    private static boolean habitrain$hasExplicitInstinctGrant(SRERole role) {
        ResourceLocation id = role.identifier();
        if (id == null) {
            return false;
        }
        RoleKey key = RoleKey.of(id);
        if (!RoleClientExtensionApi.instance().instinctsFor(key).isEmpty()) {
            return true;
        }
        for (ManagedRoleEntry<?> entry : RoleExtensionRegistry.INSTANCE.getCompiledEntries()) {
            if (entry.target() != null && entry.target().equals(key) && entry.declaration() instanceof RolePatch patch) {
                if (patch.canUseInstinct() != null && Boolean.TRUE.equals(patch.canUseInstinct().value())) {
                    return true;
                }
            }
        }
        return false;
    }
}
