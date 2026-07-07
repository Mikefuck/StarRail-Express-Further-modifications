package com.habitrain.core.client.mixin;

import io.wifi.starrailexpress.content.entity.PlayerBodyEntity;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 缓解 SRE 原版 PlayerBodyEntity.getCustomName 在客户端级别的 NPE 崩溃。
 *
 * 崩溃堆栈：
 *   NPE: MinecraftServer.method_3760() return value of
 *        PlayerBodyEntity.method_5682() is null
 *   at PlayerBodyEntity.method_5797(PlayerBodyEntity.java:96)
 *   at PlayerBodyEntity.method_5476(PlayerBodyEntity.java:80)
 *   at class_761.handler$beo000$entityculling$renderEntity
 *
 * 根因：SRE PlayerBodyEntity.getCustomName 在 line 96 调用 this.getServer()
 * （method_5682 = getServer），客户端级别实体 getServer() 返回 null，
 * 仍调用 .getPlayerList() → NPE。entityculling 渲染时触发此方法。
 *
 * 这是 SRE 原版 bug，应反馈给 SRE 作者修。本 mixin 仅作治标缓解，
 * 在 getServer()==null 时直接返回 null，避免崩溃。
 *
 * required:false 防 SRE 改名 PlayerBodyEntity 导致启动崩溃。
 */
@Mixin(value = PlayerBodyEntity.class, remap = false)
public class PlayerBodyEntityMixin {

    @Inject(
            method = "getCustomName",  // SRE 用 Mojang 官方映射，非 yarn。PlayerBodyEntity 重写 Entity.getCustomName()。
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void habitrain$nullGuardGetCustomName(CallbackInfoReturnable<Object> cir) {
        // 若 getServer() 为 null（客户端级别），直接返回 null 避免后续 NPE
        PlayerBodyEntity self = (PlayerBodyEntity) (Object) this;
        MinecraftServer server = self.getServer();
        if (server == null) {
            cir.setReturnValue(null);
        }
    }
}