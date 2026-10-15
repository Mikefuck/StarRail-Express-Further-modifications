package external;

import com.habitrain.core.api.role.v2.RoleExtensionRegistrar;
import com.habitrain.core.api.role.v2.RoleKey;
import com.habitrain.core.api.role.v2.action.*;
import com.habitrain.core.api.role.v2.client.*;
import com.habitrain.core.api.role.v2.state.*;
import com.mojang.serialization.Codec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Compile-checked downstream example: all Core dependencies are public API. */
public final class RoleClientPublicApiExample {
    private static final String MOD = "example_mod";
    private static final ResourceLocation PICK = ResourceLocation.fromNamespaceAndPath(MOD, "pick");
    private static final ResourceLocation SCREEN = ResourceLocation.fromNamespaceAndPath(MOD, "picker");

    /** Call inside the common provider for its already-declared ADD/MODIFY role. */
    public static RoleStateKey<Integer> registerCommon(RoleExtensionRegistrar registrar, RoleKey role) {
        var charges = registrar.state(RoleStateSpec.of(MOD, "charges", Integer.class).role(role)
                .codec(Codec.INT).sync(SyncPolicy.OWNER).defaultValue(() -> 0).build());
        registrar.action(RoleActionSpec.of(PICK).role(role).targetDecoder(ActionTargetCodec.PLAYER_UUID)
                .handler(context -> {
                    if (!(context.target() instanceof RoleActionTarget.Player)) return RoleActionResult.reject(RoleActionResult.TARGET);
                    return RoleActionResult.success();
                }).build());
        return charges;
    }

    public static void updateCharges(RoleStateKey<Integer> charges, ServerPlayer owner, int amount) {
        RoleStateApi.instance().set(charges, owner, amount);
    }

    /** Keep this class in the provider's client entrypoint; requiresClient() returns true. */
    @Environment(EnvType.CLIENT)
    public static final class Client {
        public static void register(RoleClientExtensionRegistrar registrar, RoleStateKey<Integer> charges,
                                    String serverEntryKey, Consumer<Integer> drawCharges) {
            registrar.screen(RoleScreenSpec.of(MOD, "picker").role(charges.role()).entryKey(serverEntryKey)
                    .kind(RoleScreenKind.PLAYER_PICK).titleKey("screen.example_mod.picker").build());
            registrar.hudWidget(ResourceLocation.fromNamespaceAndPath(MOD, "charges_hud"), serverEntryKey,
                    charges.role(), (width, height, delta) -> {
                        var mc = Minecraft.getInstance();
                        if (mc.player == null || mc.level == null) return;
                        var value = RoleStateClientApi.instance().read(charges, StateScope.PLAYER,
                                mc.level.dimension().location(), mc.player.getUUID());
                        drawCharges.accept(value.orElse(0));
                    });
        }

        public static boolean openPicker() {
            return RoleScreenClientApi.instance().open(SCREEN, result -> {
                if (result.outcome() != RoleScreenResult.Outcome.SELECTED || result.playerId() == null) return;
                var id = result.playerId();
                byte[] payload = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                        .putLong(id.getLeastSignificantBits()).array();
                RoleActionClientApi.instance().send(PICK, payload, (actionId, response) -> {});
            });
        }
    }
}
