package com.habitrain.core.game.sre;

import com.habitrain.core.HabiTrainCore;
import com.habitrain.core.api.GameModeRegistry;
import io.wifi.starrailexpress.api.GameMode;
import io.wifi.starrailexpress.api.SREGameModes;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Scans original SRE {@link SREGameModes#GAME_MODES} and registers thin Core proxies so
 * those modes appear in {@link GameModeRegistry} / mode-map vote.
 * <p>
 * Modes already owned by Core (murder, repair, blackout) are skipped to avoid duplicates.
 */
public final class SREOriginalModeBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core|SREOriginalModeBridge");

    /** SRE ids already represented by dedicated Core GameMode entries. */
    private static final Set<ResourceLocation> SKIP_SRE_IDS = Set.of(
            ResourceLocation.fromNamespaceAndPath("sre", "murder"),
            ResourceLocation.fromNamespaceAndPath("canyuesama", "repair_escape"),
            ResourceLocation.fromNamespaceAndPath("sre", "blackout")
    );

    private SREOriginalModeBridge() {}

    /**
     * Register proxies for every original SRE mode not already covered by Core.
     * Must run during mod init before {@link GameModeRegistry#freeze()}.
     */
    public static void registerAll() {
        try {
            Map<ResourceLocation, GameMode> modes = SREGameModes.GAME_MODES;
            if (modes == null || modes.isEmpty()) {
                LOGGER.warn("SREGameModes.GAME_MODES empty; no original modes bridged");
                return;
            }

            int bridged = 0;
            Set<String> seenFullIds = new HashSet<>();
            for (Map.Entry<ResourceLocation, GameMode> entry : modes.entrySet()) {
                ResourceLocation sreId = entry.getKey();
                if (sreId == null) {
                    continue;
                }
                if (SKIP_SRE_IDS.contains(sreId)) {
                    LOGGER.debug("skip already-covered SRE mode: {}", sreId);
                    continue;
                }

                String modeId = toProxyModeId(sreId);
                String fullId = HabiTrainCore.MOD_ID + ":" + modeId;
                if (!seenFullIds.add(fullId) || GameModeRegistry.isRegistered(fullId)) {
                    LOGGER.warn("skip duplicate proxy fullId={} for SRE {}", fullId, sreId);
                    continue;
                }

                GameModeRegistry.register(HabiTrainCore.MOD_ID, modeId, new SreOriginalModeProxy(sreId));
                bridged++;
                LOGGER.info("bridged original SRE mode {} → {}", sreId, fullId);
            }
            LOGGER.info("bridged {} original SRE modes into GameModeRegistry", bridged);
        } catch (Throwable t) {
            LOGGER.error("failed to bridge original SRE modes; vote list keeps Core built-ins only", t);
        }
    }

    /**
     * Core modeId segment after modId: {@code sre_proxy_<namespace>_<path>}.
     * Kept free of ':' so the full id is always a valid ResourceLocation.
     * Path characters outside {@code [a-z0-9_]} become {@code _}.
     */
    static String toProxyModeId(ResourceLocation sreId) {
        String ns = sanitizeSegment(sreId.getNamespace());
        String path = sanitizeSegment(sreId.getPath());
        return "sre_proxy_" + ns + "_" + path;
    }

    private static String sanitizeSegment(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
