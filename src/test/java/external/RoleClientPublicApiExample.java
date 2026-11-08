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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Compile-checked and unit-verified downstream example: all Core dependencies are public API. */
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

    @Test
    @SuppressWarnings("unchecked")
    void registerCommonPublishesStateAndActionThroughPublicContracts() {
        RoleKey role = RoleKey.of(MOD, "scout");
        List<RoleStateSpec<?>> registeredStates = new ArrayList<>();
        List<RoleActionSpec> registeredActions = new ArrayList<>();
        RoleExtensionRegistrar registrar = (RoleExtensionRegistrar) Proxy.newProxyInstance(
                RoleExtensionRegistrar.class.getClassLoader(),
                new Class<?>[]{RoleExtensionRegistrar.class},
                (proxy, method, args) -> {
                    if ("state".equals(method.getName()) && args != null && args.length == 1) {
                        RoleStateSpec<Object> spec = (RoleStateSpec<Object>) args[0];
                        registeredStates.add(spec);
                        return new RoleStateKey<>(spec.id(), spec.role(), spec.type());
                    }
                    if ("action".equals(method.getName()) && args != null && args.length == 1) {
                        RoleActionSpec spec = (RoleActionSpec) args[0];
                        registeredActions.add(spec);
                        return null;
                    }
                    throw new UnsupportedOperationException("Unexpected call: " + method.getName());
                });

        RoleStateKey<Integer> key = registerCommon(registrar, role);
        assertNotNull(key);
        assertEquals(ResourceLocation.fromNamespaceAndPath(MOD, "charges"), key.id());
        assertEquals(role, key.role());
        assertEquals(1, registeredStates.size());
        assertEquals(1, registeredActions.size());
        assertEquals(PICK, registeredActions.getFirst().id());
        assertEquals(role, registeredActions.getFirst().role());
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
