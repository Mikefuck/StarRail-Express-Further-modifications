package com.habitrain.core.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinErrorHandler;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Logs mixin prepare/apply failures without touching {@code HabiTrainCore}
 * (avoids SoundEvent clinit during mixin apply).
 * Returns the original {@link IMixinErrorHandler.ErrorAction} so
 * {@code required:true} still crashes and {@code required:false} still skips.
 */
public class HabiMixinPlugin implements IMixinConfigPlugin, IMixinErrorHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("habitrain_core");

    @Override
    public void onLoad(String mixinPackage) {
        Mixins.registerErrorHandlerClass(HabiMixinPlugin.class.getName());
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (targetClassName != null
                && (targetClassName.startsWith("dev.doctor4t.trainmurdermystery.")
                || targetClassName.startsWith("org.agmas.harpymodloader."))) {
            boolean present = HabiMixinPlugin.class.getClassLoader()
                    .getResource(targetClassName.replace('.', '/') + ".class") != null;
            if (!present) {
                LOGGER.debug("Skipping optional compatibility mixin {} because target {} is absent",
                        mixinClassName, targetClassName);
                return false;
            }
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public IMixinErrorHandler.ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin,
            IMixinErrorHandler.ErrorAction action) {
        if (!isCoreMixin(mixin, config)) return action;
        LOGGER.atLevel(action == ErrorAction.ERROR ? org.slf4j.event.Level.ERROR : org.slf4j.event.Level.WARN).log(
                "Mixin prepare failed: mixin={} target={} config={}",
                mixinName(mixin),
                targetNames(mixin),
                config != null ? config.getName() : "?",
                th);
        return action;
    }

    @Override
    public IMixinErrorHandler.ErrorAction onApplyError(String targetClassName, Throwable th, IMixinInfo mixin,
            IMixinErrorHandler.ErrorAction action) {
        if (!isCoreMixin(mixin, null)) return action;
        LOGGER.atLevel(action == ErrorAction.ERROR ? org.slf4j.event.Level.ERROR : org.slf4j.event.Level.WARN).log(
                "Mixin apply failed: mixin={} target={}",
                mixinName(mixin),
                targetClassName,
                th);
        return action;
    }

    private static boolean isCoreMixin(IMixinInfo mixin, IMixinConfig config) {
        if (mixin != null) return mixin.getClassName().startsWith("com.habitrain.core.");
        // Prepare failures can precede construction of IMixinInfo.
        return config != null && Set.of("habitrain_core.mixins.json", "habitrain_core.client.mixins.json",
                "habitrain_core.guards.mixins.json", "habitrain_core.rest_area.mixins.json").contains(config.getName());
    }

    private static String mixinName(IMixinInfo mixin) {
        return mixin != null ? mixin.getClassName() : "?";
    }

    private static String targetNames(IMixinInfo mixin) {
        if (mixin == null || mixin.getTargetClasses() == null) {
            return "?";
        }
        return mixin.getTargetClasses().toString();
    }
}
