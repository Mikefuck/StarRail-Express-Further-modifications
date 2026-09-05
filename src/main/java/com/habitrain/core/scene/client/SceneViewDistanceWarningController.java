package com.habitrain.core.scene.client;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.client.gui.GameEndOverlayState;
import com.habitrain.core.client.gui.VoteLaunchOverlayState;
import com.habitrain.core.scene.model.SceneProfile;
import com.habitrain.core.scene.model.SceneRuntimeState;
import com.habitrain.core.scene.model.SceneViewDistanceWarningSession;
import com.habitrain.core.scene.model.SceneVisibilityAdvisor;
import io.wifi.starrailexpress.client.gui.RoundTextRenderer;
import io.wifi.starrailexpress.event.OnRoundStartWelcomeTimmer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Connects P13's testable lifecycle policy to SRE events and the live scene runtime. */
@Environment(EnvType.CLIENT)
public final class SceneViewDistanceWarningController {
    private static final SceneViewDistanceWarningController INSTANCE =
            new SceneViewDistanceWarningController();

    private final SceneViewDistanceWarningSession session = new SceneViewDistanceWarningSession();
    private boolean initialized;

    private SceneViewDistanceWarningController() {}

    public static SceneViewDistanceWarningController getInstance() {
        return INSTANCE;
    }

    public synchronized void init() {
        if (initialized) return;
        initialized = true;
        OnRoundStartWelcomeTimmer.EVENT.register((player, welcomeTime) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && player == minecraft.player) {
                session.onWelcomeTimer(welcomeTime);
            }
        });
    }

    public void onRoundStarted() {
        session.startRound();
        SceneViewDistanceWarningHud.reset();
    }

    public void reset() {
        session.reset();
        SceneViewDistanceWarningHud.reset();
    }

    public void tick(Minecraft minecraft) {
        SceneViewDistanceWarningHud.tick();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return;

        SceneRenderRuntime runtime = SceneRenderRuntime.getInstance();
        SceneRuntimeState state = runtime.getCurrentState();
        boolean meshReady = runtime.isRuntimeMeshReady();
        String dimension = minecraft.level.dimension().location().toString();
        var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        boolean displayUnblocked = minecraft.screen == null
                && !VoteLaunchOverlayState.isBlockingNow()
                && !GameEndOverlayState.isBlockingNow()
                && RoundTextRenderer.welcomeTime <= 0
                && RoundTextRenderer.endTime <= 0;
        String signature = sceneSignature(state, meshReady);
        SceneViewDistanceWarningSession.CheckReason reason = session.tick(
                new SceneViewDistanceWarningSession.TickInput(
                        camera.x, camera.y, camera.z, dimension, signature, displayUnblocked));
        if (reason == SceneViewDistanceWarningSession.CheckReason.NONE) return;
        evaluate(minecraft, state, meshReady, dimension, camera.x, camera.y, camera.z, reason);
    }

    private void evaluate(Minecraft minecraft, SceneRuntimeState state, boolean meshReady,
                          String dimension, double cameraX, double cameraY, double cameraZ,
                          SceneViewDistanceWarningSession.CheckReason reason) {
        if (state == null || !state.isActive()) {
            HabiTrainCore.LOGGER.debug("P13 scene view-distance check skipped ({}): runtime inactive", reason);
            return;
        }
        SceneProfile profile = state.getProfile();
        if (profile == null || !profile.isEnabled() || profile.getSourceBounds().isEmpty()
                || state.getAssetHash().isBlank() || !meshReady
                || !profile.getDimension().equals(dimension)) {
            HabiTrainCore.LOGGER.debug(
                    "P13 scene view-distance check skipped ({}): map={}, enabled={}, bounds={}, asset={}, meshReady={}, profileDimension={}, currentDimension={}",
                    reason, state.getMapKey(), profile != null && profile.isEnabled(),
                    profile != null ? profile.getSourceBounds() : null,
                    !state.getAssetHash().isBlank(), meshReady,
                    profile != null ? profile.getDimension() : "", dimension);
            return;
        }

        SceneProjectionDiagnostics.Snapshot projection = SceneProjectionDiagnostics.snapshot();
        SceneVisibilityAdvisor.Advice advice = SceneVisibilityAdvisor.advise(profile,
                cameraX, cameraY, cameraZ,
                projection.clientMaximumChunks(), projection.clientConfiguredChunks(),
                projection.effectiveChunks());
        SceneViewDistanceWarningSession.WarningKind warning =
                SceneViewDistanceWarningSession.warningKind(advice);
        String mapKey = state.getMapKey();
        if (warning == SceneViewDistanceWarningSession.WarningKind.NONE || session.hasWarned(mapKey)) {
            return;
        }

        Component message = switch (warning) {
            case CLIENT_SETTING_LOW -> Component.translatable(
                    "hud.habitrain_core.scene_view_distance.client_low", advice.rawRequiredChunks());
            case SERVER_LIMIT_LOW -> Component.translatable(
                    "hud.habitrain_core.scene_view_distance.server_low",
                    projection.serverLimitChunks(), advice.rawRequiredChunks());
            case NONE -> null;
        };
        if (message == null) return;
        SceneViewDistanceWarningHud.show(message);
        session.markWarned(mapKey);
        HabiTrainCore.LOGGER.info(
                "Scene view-distance warning shown: map={}, reason={}, client={}, effective={}, recommended={}",
                mapKey, warning, advice.clientConfiguredChunks(), advice.effectiveChunks(),
                advice.rawRequiredChunks());
    }

    private static String sceneSignature(SceneRuntimeState state, boolean meshReady) {
        if (state == null) return "missing";
        return state.isActive() + "|" + state.getMapKey() + "|" + state.getProfileRevision()
                + "|" + state.getAssetHash() + "|" + state.getProfile().hashCode()
                + "|mesh=" + meshReady;
    }
}
