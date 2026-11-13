package com.habitrain.core.scene.client.replay;

import com.habitrain.core.api.match.replay.LastMatchReplay;
import com.habitrain.core.client.gui.OptionVoteTexts;
import com.habitrain.core.scene.replay.ReplayDeathIcons;
import com.habitrain.core.scene.replay.ReplayStagePlan;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Localised labels for the board, mirroring the settlement screen's wording rules. */
final class ReplayBoardTexts {
    private ReplayBoardTexts() {}

    static final String KEY = "habitrain_core.replay_board.";

    static Component tr(String suffix, Object... args) {
        return Component.translatable(KEY + suffix, args);
    }

    static Component winTitle(LastMatchReplay replay) {
        String status = replay.winStatus();
        boolean customComponent = "CUSTOM_COMPONENT".equals(status);
        boolean custom = "CUSTOM".equals(status) || customComponent;
        if (!replay.customTitleJson().isBlank()) {
            Component parsed = parse(replay.customTitleJson());
            if (parsed != null && !parsed.getString().isBlank()) return parsed;
        }
        Language language = Language.getInstance();
        if (custom && !replay.customWinId().isBlank()) {
            String id = shortId(replay.customWinId());
            if (language.has("announcement.star.win." + id)) return Component.translatable("announcement.star.win." + id);
            if (language.has("announcement.star.role." + id)) {
                return Component.translatable("gameend.habitrain_core.win.custom", Component.translatable("announcement.star.role." + id));
            }
            return Component.translatable("gameend.habitrain_core.win.custom", Component.literal(id));
        }
        String id = upstreamWinnerId(status);
        if (language.has("announcement.star.win." + id)) return Component.translatable("announcement.star.win." + id);
        return Component.translatable("gameend.habitrain_core.win.custom", Component.literal(id));
    }

    static Component winSubtitle(LastMatchReplay replay) {
        if (!replay.customSubtitleJson().isBlank()) {
            Component parsed = parse(replay.customSubtitleJson());
            if (parsed != null && !parsed.getString().isBlank()) return parsed;
        }
        String status = replay.winStatus();
        boolean custom = "CUSTOM".equals(status) || "CUSTOM_COMPONENT".equals(status);
        String id = custom ? shortId(replay.customWinId()) : "TIME".equals(status) ? "time" : upstreamWinnerId(status);
        String key = "gameend.habitrain_core.tag." + id;
        if (!id.isBlank() && Language.getInstance().has(key)) return Component.translatable(key);
        return mode(replay.modeId());
    }

    static int winColor(LastMatchReplay replay) {
        String status = replay.winStatus();
        if ("CUSTOM_COMPONENT".equals(status) || "CUSTOM".equals(status) || replay.customColor() != 0) {
            if (replay.customColor() != 0) return ReplayPalette.opaque(replay.customColor(), 0xFFFFE1A0);
        }
        return switch (status) {
            case "KILLERS" -> 0xFFE0383E;
            case "PASSENGERS" -> 0xFFFFE1A0;
            case "TIME" -> 0xFFD9AE59;
            case "LOOSE_END" -> 0xFFD02A2A;
            case "GAMBLER" -> 0xFFC266E0;
            case "RECORDER", "NO_PLAYER", "NONE" -> 0xFFC0C0C0;
            case "NIAN_SHOU" -> 0xFFFF6A2A;
            case "LOVERS" -> 0xFFF38AFF;
            default -> 0xFFE7C77E;
        };
    }

    static Component mode(String modeId) {
        if (modeId == null || modeId.isBlank()) return Component.empty();
        String key = OptionVoteTexts.optionLangKey(modeId);
        if (Language.getInstance().has(key)) return Component.translatable(key);
        return Component.literal(shortId(modeId));
    }

    static Component role(String roleId) {
        if (roleId == null || roleId.isBlank()) return Component.literal("?");
        String id = shortId(roleId);
        String key = "announcement.star.role." + id;
        if (Language.getInstance().has(key)) return Component.translatable(key);
        return Component.literal(id.replace('_', ' '));
    }

    /** Death reason / weapon label for briefings and summaries. */
    static Component weapon(LastMatchReplay.Node node) {
        String detail = node.detail();
        if (node.kind() == LastMatchReplay.Kind.GUNSHOT) return item(detail);
        ResourceLocation id = ResourceLocation.tryParse(detail == null ? "" : detail);
        if (id != null) {
            String key = "death_reason." + id.getNamespace() + "." + id.getPath();
            if (Language.getInstance().has(key)) return Component.translatable(key);
        }
        ReplayDeathIcons.Icon icon = ReplayDeathIcons.classify(detail);
        return tr("weapon." + icon.name().toLowerCase(Locale.ROOT));
    }

    static Component item(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId == null ? "" : itemId);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) return BuiltInRegistries.ITEM.get(id).getDescription();
        return tr("weapon.revolver");
    }

    static Component name(@Nullable LastMatchReplay.Participant p) {
        if (p == null || p.name().isBlank()) return tr("unknown_player");
        return Component.literal(p.name());
    }

    static String clock(double ticks) {
        return ReplayStagePlan.clock(ticks);
    }

    static Component killLine(String key, LastMatchReplay.Node node, ReplayBoardModel model) {
        MutableComponent killer = node.actor() == null ? tr("environment").copy()
                : name(model.participantData(node.actor())).copy();
        return tr(key, killer, name(model.participantData(node.target())), weapon(node), clock(node.tick()));
    }

    private static @Nullable Component parse(String json) {
        try {
            RegistryAccess access = Minecraft.getInstance().level != null
                    ? Minecraft.getInstance().level.registryAccess() : RegistryAccess.EMPTY;
            return Component.Serializer.fromJson(json, (HolderLookup.Provider) access);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String upstreamWinnerId(String status) {
        return switch (status == null ? "" : status) {
            case "KILLERS" -> "killers";
            case "PASSENGERS", "TIME" -> "passengers";
            case "LOOSE_END" -> "loose_end";
            case "GAMBLER" -> "gambler";
            case "RECORDER" -> "recorder";
            case "NO_PLAYER" -> "noplayer";
            case "NONE" -> "none";
            case "NIAN_SHOU" -> "nianshou";
            case "LOVERS" -> "lovers";
            default -> "unknown";
        };
    }

    static String shortId(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return (colon >= 0 ? id.substring(colon + 1) : id).toLowerCase(Locale.ROOT);
    }
}
