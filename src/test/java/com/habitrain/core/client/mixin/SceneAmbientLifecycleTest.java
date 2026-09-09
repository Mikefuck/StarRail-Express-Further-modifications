package com.habitrain.core.client.mixin;

import com.habitrain.core.client.RepairModeClientState;
import com.habitrain.core.client.gui.VoteLaunchOverlayState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.junit.jupiter.api.Assertions.*;

class SceneAmbientLifecycleTest {
    private static final class Loop extends RepairAmbientLoopMixin {
        Loop() {
            super(SoundEvent.createVariableRangeEvent(ResourceLocation.parse("test:train")),
                    SoundSource.AMBIENT, RandomSource.create());
            volume = 0.6f;
        }

        @Override public void tick() {}
        float volume() { return volume; }
    }

    @AfterEach
    void reset() {
        RepairModeClientState.reset();
        VoteLaunchOverlayState.setActive(false);
    }

    @Test
    void newLoopStartsSilentButRemainsEligibleForPlayback() throws Exception {
        var sound = new Loop();
        invoke(sound, "habitrain$startSilent");
        assertEquals(0.0f, sound.volume());
        assertTrue(sound.canStartSilent());
        assertFalse(sound.isStopped());
    }

    @Test
    void launchStopsAlreadyPlayingLoopInsteadOfOnlyBlockingNewSounds() throws Exception {
        var sound = new Loop();
        VoteLaunchOverlayState.setActive(true);
        assertTrue(invoke(sound, "habitrain$stopSceneAmbienceForRepairer").isCancelled());
        assertEquals(0.0f, sound.volume());
        assertTrue(sound.isStopped());
    }

    @Test
    void normalAmbienceContinuesOutsideLaunchAndRepair() throws Exception {
        var sound = new Loop();
        assertFalse(invoke(sound, "habitrain$stopSceneAmbienceForRepairer").isCancelled());
        assertFalse(sound.isStopped());
        assertEquals(0.6f, sound.volume());
    }

    @Test
    void shadowFieldsAndInjectedMethodsExistInActualUpstreamDependency() throws Exception {
        ClassNode ambience = read("MyBackgroundAmbience");
        assertTrue(ambience.fields.stream().anyMatch(f -> f.name.equals("predicate")
                && f.desc.equals("Ldev/doctor4t/ratatouille/client/util/ambience/BackgroundAmbience$PlayPredicate;")));
        assertTrue(ambience.fields.stream().anyMatch(f -> f.name.equals("soundInstance")
                && f.desc.equals("Lnet/minecraft/client/resources/sounds/SoundInstance;")));
        assertTrue(ambience.methods.stream().anyMatch(m -> m.name.equals("tryStarting")
                && m.desc.equals("(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/client/sounds/SoundManager;)Z")));
        ClassNode loop = read("MyBackgroundAmbientLoop");
        assertTrue(loop.methods.stream().anyMatch(m -> m.name.equals("tick") && m.desc.equals("()V")));
        assertEquals(1, loop.methods.stream().filter(m -> m.name.equals("<init>")).count());
    }

    private CallbackInfo invoke(Loop sound, String name) throws Exception {
        var method = RepairAmbientLoopMixin.class.getDeclaredMethod(name, CallbackInfo.class);
        method.setAccessible(true);
        var callback = new CallbackInfo(name, true);
        method.invoke(sound, callback);
        return callback;
    }

    private ClassNode read(String name) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "io/wifi/starrailexpress/client/util/" + name + ".class")) {
            assertNotNull(stream);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
