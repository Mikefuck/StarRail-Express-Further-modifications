package com.habitrain.core.scene.client;

import com.habitrain.core.client.RepairModeClientState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SceneRepairAmbienceTest {
    @AfterEach
    void resetRepairState() {
        RepairModeClientState.reset();
    }

    private SceneAmbientSoundController.MovingSceneLoopSound sound() {
        return new SceneAmbientSoundController.MovingSceneLoopSound(
                SoundEvent.createVariableRangeEvent(ResourceLocation.parse("test:ambience")),
                0.8f, 1.0f, 4);
    }

    // 无客户端音频资源解析器；读取实例自身的音量，避免 getVolume 访问未解析的 Sound。
    private float rawVolume(SceneAmbientSoundController.MovingSceneLoopSound sound) {
        try {
            var field = net.minecraft.client.resources.sounds.AbstractSoundInstance.class.getDeclaredField("volume");
            field.setAccessible(true);
            return field.getFloat(sound);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void playingSoundMutesDuringRepairAndResumesAfterExit() {
        var sound = sound();
        for (int i = 0; i < 4; i++) sound.tick();
        assertEquals(0.8f, rawVolume(sound));
        RepairModeClientState.setRepairing(true);
        sound.tick();
        assertEquals(0.0f, rawVolume(sound));
        assertFalse(sound.isStopped());
        RepairModeClientState.setRepairing(false);
        sound.tick();
        assertEquals(0.2f, rawVolume(sound));
    }

    @Test
    void newSceneIsSilentAndCanResumeWithoutAnotherScenePacket() {
        RepairModeClientState.setRepairing(true);
        var sound = sound();
        assertTrue(sound.canStartSilent());
        assertEquals(0.0f, rawVolume(sound));
        sound.tick();
        assertEquals(0.0f, rawVolume(sound));
        RepairModeClientState.setRepairing(false);
        sound.tick();
        assertTrue(rawVolume(sound) > 0.0f);
    }

    @Test
    void oldFadingSoundStillStopsWhileRepairing() {
        var sound = sound();
        for (int i = 0; i < 4; i++) sound.tick();
        sound.fadeOut();
        RepairModeClientState.setRepairing(true);
        for (int i = 0; i < 4; i++) {
            sound.tick();
            assertEquals(0.0f, rawVolume(sound));
        }
        assertTrue(sound.isStopped());
    }
}
