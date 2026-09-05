package com.habitrain.core.scene.client;

import com.habitrain.core.scene.asset.SceneRegistryFingerprint;
import com.habitrain.core.scene.network.SceneStagingDecisionC2S;
import com.habitrain.core.scene.network.SceneStagingOfferS2C;
import com.habitrain.core.scene.network.SceneStagingReportC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side state for the administrator-only staging diagnosis and confirmation UI. */
public final class SceneStagingClientController {
    public enum Action {
        BUILD,
        DIAGNOSING,
        POLICY_BLOCKED,
        AWAITING_CONFIRMATION
    }

    private static final SceneStagingClientController INSTANCE = new SceneStagingClientController();
    public static SceneStagingClientController getInstance() { return INSTANCE; }

    private static final class Session {
        final SceneStagingOfferS2C offer;
        volatile Action action = Action.DIAGNOSING;

        Session(SceneStagingOfferS2C offer) { this.offer = offer; }
    }

    private final Map<String, Session> sessionsByMap = new ConcurrentHashMap<>();

    private SceneStagingClientController() {}

    public void acceptOffer(SceneStagingOfferS2C offer) {
        if (offer == null || offer.descriptor() == null || !offer.descriptor().isValid()) return;
        sessionsByMap.clear();
        Session session = new Session(offer);
        sessionsByMap.put(offer.mapKey(), session);
        inspect(session);
    }

    private void inspect(Session session) {
        session.action = Action.DIAGNOSING;
        SceneStagingOfferS2C offer = session.offer;
        SceneRenderRuntime.getInstance().inspectStagingAssetDetailed(offer.descriptor())
                .whenComplete((inspection, error) -> Minecraft.getInstance().execute(() -> {
                    Session current = sessionsByMap.get(offer.mapKey());
                    if (current != session || System.currentTimeMillis() >= offer.expiresAt()) return;
                    boolean passed = error == null && inspection != null && inspection.accepted();
                    if (!passed && error == null && inspection != null
                            && inspection.isPolicyOnlyFailure()) {
                        // Keep the private staging file alive. The administrator can inspect the
                        // reported block IDs, switch to SKIP_AND_WARN, and retry without capturing
                        // or downloading the region again. No publication occurs until the server
                        // later accepts a successful report and receives an explicit Publish click.
                        session.action = Action.POLICY_BLOCKED;
                        if (Minecraft.getInstance().player != null) {
                            Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                                    "screen.habitrain_core.scene_motion.message_policy_blocked"), false);
                        }
                        return;
                    }
                    if (ClientPlayNetworking.canSend(SceneStagingReportC2S.TYPE)) {
                        ClientPlayNetworking.send(new SceneStagingReportC2S(
                                offer.stagingId(), offer.mapKey(), offer.descriptor().sha256(), passed,
                                SceneRegistryFingerprint.calculate(), environmentSummary()));
                    }
                }));
    }

    public boolean retryInspection(String mapKey) {
        Session session = sessionsByMap.get(mapKey == null ? "" : mapKey);
        if (session == null || session.action != Action.POLICY_BLOCKED
                || System.currentTimeMillis() >= session.offer.expiresAt()) {
            return false;
        }
        inspect(session);
        return true;
    }

    public Action getAction(String mapKey) {
        Session session = sessionsByMap.get(mapKey == null ? "" : mapKey);
        if (session == null) return Action.BUILD;
        if (System.currentTimeMillis() >= session.offer.expiresAt()) {
            sessionsByMap.remove(session.offer.mapKey(), session);
            return Action.BUILD;
        }
        return session.action;
    }

    public void handleProgress(String mapKey, String state) {
        Session session = sessionsByMap.get(mapKey == null ? "" : mapKey);
        if (session == null) return;
        if ("AWAITING_CONFIRMATION".equalsIgnoreCase(state)) {
            session.action = Action.AWAITING_CONFIRMATION;
        } else if ("COMPLETED".equalsIgnoreCase(state)
                || "FAILED".equalsIgnoreCase(state)
                || "CANCELLED".equalsIgnoreCase(state)
                || "EXPIRED".equalsIgnoreCase(state)) {
            sessionsByMap.remove(session.offer.mapKey(), session);
        }
    }

    public boolean sendDecision(String mapKey, boolean publish) {
        Session session = sessionsByMap.get(mapKey == null ? "" : mapKey);
        if (session == null || !ClientPlayNetworking.canSend(SceneStagingDecisionC2S.TYPE)) return false;
        if (publish && session.action != Action.AWAITING_CONFIRMATION) return false;
        SceneStagingOfferS2C offer = session.offer;
        ClientPlayNetworking.send(new SceneStagingDecisionC2S(
                offer.stagingId(), offer.mapKey(), offer.descriptor().sha256(), publish));
        if (!publish) sessionsByMap.remove(offer.mapKey(), session);
        return true;
    }

    public void reset() {
        sessionsByMap.clear();
    }

    private static String environmentSummary() {
        FabricLoader loader = FabricLoader.getInstance();
        return "fabric_renderer=true,sodium=" + loader.isModLoaded("sodium")
                + ",iris=" + loader.isModLoaded("iris")
                + ",indium=" + loader.isModLoaded("indium");
    }
}
