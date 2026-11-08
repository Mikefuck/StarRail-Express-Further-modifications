package com.habitrain.core.game.sre.match;

import net.minecraft.server.level.ServerLevel;

/** Server-thread owner of the process-wide SRE state. No player/world references are retained. */
public final class ServerMatchContext {
    public static final SingleMatchGate GATE = new SingleMatchGate();
    private ServerMatchContext() {}
    public static String dimension(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Match transitions require server thread");
        return level.dimension().location().toString();
    }
    public static void release(ServerLevel level) { GATE.release(dimension(level)); }
    public static void tick(net.minecraft.server.MinecraftServer server) {
        var snapshot = GATE.snapshot();
        if (!GATE.isNativeMatch() || snapshot.phase() == SingleMatchGate.Phase.LOBBY) return;
        var id = net.minecraft.resources.ResourceLocation.tryParse(snapshot.dimension());
        if (id == null) return;
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, id));
        if (level == null) return;
        var status = io.wifi.starrailexpress.cca.SREGameWorldComponent.KEY.get(level).getGameStatus();
        if (status == io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.ACTIVE) {
            GATE.transition(snapshot.dimension(), SingleMatchGate.Phase.RUNNING);
            com.habitrain.core.game.sre.mode.ForcedReadyJoinGate.clear();
        } else if (status == io.wifi.starrailexpress.cca.SREGameWorldComponent.GameStatus.STOPPING) {
            GATE.transition(snapshot.dimension(), SingleMatchGate.Phase.SETTLING);
        }
    }
}
