package com.habitrain.core.game.sre.role;

import com.habitrain.core.client.gui.AppleShopPriceDisplay;
import com.habitrain.core.game.sre.role.shop.ApplePriceState;
import com.habitrain.core.game.sre.role.shop.AppleShopStateCodec;
import com.habitrain.core.game.sre.role.shop.AppleShopSignature;
import io.wifi.starrailexpress.util.ShopEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real stacks, shop entries and CCA sync format in the isolated bootstrap test JVM. */
class SnakeFruitRuntimeTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void appleShopCosts150AndOnlyTaggedApplesAreTools() {
        var entries = HabiRoleShops.snakeFruitShop();
        assertEquals(1, entries.size());
        assertEquals(150, entries.getFirst().price());
        assertEquals(ShopEntry.Currency.MONEY, entries.getFirst().currency());
        var apple = entries.getFirst().stack();
        assertTrue(HabiRoleItems.isRedApple(apple));
        assertFalse(HabiRoleItems.isRedApple(Items.APPLE.getDefaultInstance()));
        var renamed = Items.APPLE.getDefaultInstance();
        renamed.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, apple.getHoverName());
        assertFalse(HabiRoleItems.isRedApple(renamed));
        assertFalse(HabiRoleItems.isRedApple(HabiRoleItems.createPepperSpray()));
        var interactions = HabiRoleHooks.snakeFruit().interaction();
        assertEquals(InteractionResult.FAIL, interactions.useItem(null, apple, InteractionHand.MAIN_HAND, null));
        assertEquals(InteractionResult.PASS, interactions.useItem(null,
                Items.APPLE.getDefaultInstance(), InteractionHand.OFF_HAND, null));
    }

    @Test void applePurchaseChecksReadinessAndOnlyRecordsSuccessfulDelivery() throws Exception {
        String entryClass = HabiRoleShops.snakeFruitShop().getFirst().getClass().getName().replace('.', '/');
        var gateCalls = calls(entryClass, "canBuy");
        assertTrue(gateCalls.contains("com/habitrain/core/game/sre/role/shop/SnakeFruitService"
                + ".purchaseCooldownTicks(Lnet/minecraft/world/entity/player/Player;)J"));
        var buyCalls = calls(entryClass, "onBuy");
        assertEquals(java.util.List.of(entryClass + ".canBuy(Lnet/minecraft/world/entity/player/Player;)Z",
                "io/wifi/starrailexpress/util/ShopEntry.onBuy(Lnet/minecraft/world/entity/player/Player;)Z",
                "com/habitrain/core/game/sre/role/shop/SnakeFruitService.recordApplePurchase(Lnet/minecraft/world/entity/player/Player;)V"),
                buyCalls);
        assertTrue(calls("com/habitrain/core/game/sre/role/shop/SnakeFruitService", "use")
                .stream().noneMatch(c -> c.contains("purchaseCooldown") || c.contains("getCooldowns")));
    }

    @Test void stackComponentsPriceAndQuantityAllBelongToEntryIdentity() {
        var apple = HabiRoleItems.createRedApple();
        var first = new ShopEntry(apple, 150, ShopEntry.Type.TOOL);
        var equal = new ShopEntry(apple.copy(), 150, ShopEntry.Type.TOOL);
        assertEquals(AppleShopSignature.of(first), AppleShopSignature.of(equal));
        assertEquals(AppleShopSignature.of(first).hashCode(), AppleShopSignature.of(equal).hashCode());
        assertNotEquals(AppleShopSignature.of(first), AppleShopSignature.of(new ShopEntry(
                Items.APPLE.getDefaultInstance(), 150, ShopEntry.Type.TOOL)));
        assertNotEquals(AppleShopSignature.of(first), AppleShopSignature.of(new ShopEntry(apple, 200, ShopEntry.Type.TOOL)));
        var two = apple.copyWithCount(2);
        assertNotEquals(AppleShopSignature.of(first), AppleShopSignature.of(new ShopEntry(two, 150, ShopEntry.Type.TOOL)));
        assertEquals(AppleShopSignature.of(first), AppleShopSignature.of(new ShopEntry(apple, 100, ShopEntry.Type.TOOL), 150));
    }

    @Test void targetSyncRoundTripsEachSlotWithoutWritingPermanentState() throws Exception {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var signature = AppleShopSignature.of(HabiRoleShops.snakeFruitShop().getFirst());
        CompoundTag incoming = new CompoundTag();
        incoming.putString("role", "test:target");
        incoming.putLong("settles", 1200);
        incoming.putLong("seed", 42);
        incoming.putLong("revision", 99);
        incoming.putBoolean("settled", true);
        incoming.putInt("size", 2);
        ListTag prices = new ListTag();
        for (int index = 0; index < 2; index++) {
            CompoundTag price = signature.save(registries);
            price.putInt("index", index);
            price.putInt("normal", 100);
            price.putInt("price", index == 0 ? 25 : 900);
            prices.add(price);
        }
        incoming.put("prices", prices);
        var state = new ApplePriceState<AppleShopSignature>();
        int size = AppleShopStateCodec.read(state, incoming, registries);
        CompoundTag outgoing = new CompoundTag();
        AppleShopStateCodec.write(state, size, outgoing, registries);
        assertEquals(incoming.getString("role"), outgoing.getString("role"));
        assertEquals(1200, outgoing.getLong("settles"));
        assertEquals(42, outgoing.getLong("seed"));
        assertEquals(99, outgoing.getLong("revision"));
        assertTrue(outgoing.getBoolean("settled"));
        var result = outgoing.getList("prices", 10);
        assertEquals(2, result.size());
        for (int i = 0; i < result.size(); i++) {
            var tag = result.getCompound(i);
            assertEquals(tag.getInt("index") == 0 ? 25 : 900, tag.getInt("price"));
            assertEquals(signature, AppleShopSignature.load(tag, registries));
        }
        // An empty owner sync must replace the previous price map, not merge it.
        size = AppleShopStateCodec.read(state, new CompoundTag(), registries);
        outgoing = new CompoundTag();
        AppleShopStateCodec.write(state, size, outgoing, registries);
        assertTrue(outgoing.getList("prices", 10).isEmpty());
        String component = "com/habitrain/core/game/sre/role/shop/AppleShopComponent";
        assertTrue(calls(component, "writeToNbt").isEmpty());
        assertTrue(calls(component, "writeToSyncNbt").stream().anyMatch(c -> c.contains("AppleShopStateCodec.write")));
        assertTrue(calls(component, "readFromSyncNbt").stream().anyMatch(c -> c.contains("AppleShopStateCodec.read")));
    }

    @Test void scrambleSyncConcealsFinalPricesButReproducesTheSameDisplayedQuote() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var signature = AppleShopSignature.of(HabiRoleShops.snakeFruitShop().getFirst());
        var server = new ApplePriceState<AppleShopSignature>();
        server.apply("test:target", 1200, 42, 99, false,
                java.util.Map.of(0, new ApplePriceState.Quote<>(signature, 150, 990)));
        CompoundTag tag = new CompoundTag();
        AppleShopStateCodec.write(server, 1, tag, registries);
        assertEquals(0, tag.getList("prices", 10).getCompound(0).getInt("price"));
        var client = new ApplePriceState<AppleShopSignature>();
        AppleShopStateCodec.read(client, tag, registries);
        for (long tick = 1000; tick < 1200; tick++) {
            assertEquals(server.offer("test:target", 0, signature, tick), client.offer("test:target", 0, signature, tick));
        }
        server.settle(1200);
        AppleShopStateCodec.write(server, 1, tag, registries);
        AppleShopStateCodec.read(client, tag, registries);
        assertEquals(990, client.quote("test:target", 0, signature, 90000).price());
    }

    @Test void quotePacketRoundTripsWithoutAnyClientSuppliedPrice() {
        var payload = new com.habitrain.core.network.AppleShopBuyPayload(3, 0x123456789L, -1);
        var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            com.habitrain.core.network.AppleShopBuyPayload.CODEC.encode(buf, payload);
            assertEquals(payload, com.habitrain.core.network.AppleShopBuyPayload.CODEC.decode(buf));
        } finally { buf.release(); }
    }

    @Test void successfulAppleUseOnlyNotifiesTheTarget() throws Exception {
        var calls = calls("com/habitrain/core/game/sre/role/shop/SnakeFruitService", "use");
        assertEquals(1, calls.stream().filter(c -> c.endsWith(
                ".displayClientMessage(Lnet/minecraft/network/chat/Component;Z)V")).count());
        try (var source = getClass().getClassLoader().getResourceAsStream(
                "com/habitrain/core/game/sre/role/shop/SnakeFruitService.class")) {
            assertNotNull(source);
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(source).accept(node, 0);
            var messages = new java.util.ArrayList<String>();
            node.methods.stream().filter(m -> m.name.equals("use")).forEach(m -> {
                for (var instruction : m.instructions) {
                    if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode constant
                            && constant.cst instanceof String text && text.startsWith("message.")) {
                        messages.add(text);
                    }
                }
            });
            assertEquals(java.util.List.of("message.habitrain_core.snake_fruit.affected"), messages);
        }
    }

    @Test void mixinTargetsMatchTheActualUpstreamBytecode() throws Exception {
        var clientCalls = calls("io/wifi/starrailexpress/client/gui/screen/ingame/LimitedInventoryScreen$StoreItemWidget", "renderWidget");
        assertEquals(1, clientCalls.stream().filter(c -> c.equals(
                "io/wifi/starrailexpress/cca/DynamicShopComponent.effectivePrice(Lnet/minecraft/resources/ResourceLocation;I)I")).count());
        assertEquals(1, clientCalls.stream().filter(c -> c.equals(
                "net/minecraft/client/gui/GuiGraphics.renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V")).count());
        var serverCalls = calls("io/wifi/starrailexpress/cca/SREPlayerShopComponent", "tryBuy");
        assertEquals(1, serverCalls.stream().filter(c -> c.equals(
                "io/wifi/starrailexpress/cca/DynamicShopComponent.effectivePrice(Lio/wifi/starrailexpress/util/ShopEntry;)I")).count());
    }

    private static java.util.List<String> calls(String className, String methodName) throws Exception {
        var result = new java.util.ArrayList<String>();
        try (var source = SnakeFruitRuntimeTest.class.getClassLoader().getResourceAsStream(className + ".class")) {
            assertNotNull(source);
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(source).accept(node, 0);
            node.methods.stream().filter(m -> m.name.equals(methodName)).forEach(m -> {
                for (var instruction : m.instructions) {
                    if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                        result.add(call.owner + "." + call.name + call.desc);
                    }
                }
            });
        }
        return result;
    }

    @Test void priceChangeIsImmediatelyToTheRightWithGreenOrRedStyle() {
        var discounted = AppleShopPriceDisplay.amount(200, 100);
        assertEquals("100 -50%", discounted.getString());
        assertEquals(ChatFormatting.GREEN.getColor(), discounted.getSiblings().getFirst().getStyle().getColor().getValue());
        var increased = AppleShopPriceDisplay.amount(200, 600);
        assertEquals("600 +200%", increased.getString());
        assertEquals(ChatFormatting.RED.getColor(), increased.getSiblings().getFirst().getStyle().getColor().getValue());
        assertEquals("500 +∞%", AppleShopPriceDisplay.amount(0, 500).getString());
    }
}
