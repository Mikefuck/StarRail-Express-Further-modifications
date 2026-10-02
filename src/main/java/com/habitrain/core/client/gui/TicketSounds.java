package com.habitrain.core.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * 车票转场的音效点缀：纸片飘落、落定、盖章、撕票与燃烧，全部走 UI 声道且音量克制。
 * 结算转场另有一组压低的恐怖音色：心跳、滴墨、打字与远处的钟声。
 */
final class TicketSounds {
    private TicketSounds() {
    }

    /** 纸票飘落到位。 */
    static void flutter(float pitch) {
        play(SoundEvents.BOOK_PAGE_TURN, pitch, 0.4f);
    }

    /** 大票落定。 */
    static void land(float pitch) {
        play(SoundEvents.BOOK_PUT, pitch, 0.75f);
    }

    /** 盖章：低沉的一声「咚」。 */
    static void stamp() {
        play(SoundEvents.BOOK_PUT, 0.62f, 1.0f);
    }

    /** 沿撕票线撕开 / 被卷走。 */
    static void tear(float pitch) {
        play(SoundEvents.BOOK_PAGE_TURN, pitch, 0.9f);
    }

    /** 车票迅速变旧起皱：低沉的纸张揉动声。 */
    static void crumple() {
        play(SoundEvents.BOOK_PAGE_TURN, 0.55f, 0.8f);
    }

    /** 纸边被点燃。 */
    static void ignite() {
        play(SoundEvents.FLINTANDSTEEL_USE, 1.1f, 0.6f);
    }

    /** 燃烧的噼啪声。 */
    static void burn(float pitch) {
        play(SoundEvents.FIRE_AMBIENT, pitch, 0.85f);
    }

    /** 最后一点余烬熄灭。 */
    static void smother() {
        play(SoundEvents.FIRE_EXTINGUISH, 1.6f, 0.18f);
    }

    /** 沉闷的心跳。 */
    static void heartbeat(float pitch, float volume) {
        play(SoundEvents.WARDEN_HEARTBEAT, pitch, volume);
    }

    /** 印泥顺着章框往下滴。 */
    static void drip(float pitch) {
        play(SoundEvents.POINTED_DRIPSTONE_DRIP_WATER, pitch, 0.7f);
    }

    /** 打字机的一下击键。 */
    static void type(float pitch) {
        play(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.07f);
    }

    /** 远处走调的钟声。 */
    static void toll() {
        play(SoundEvents.BELL_BLOCK, 0.5f, 0.45f);
    }

    /** 人影浮现时的一阵低语。 */
    static void whisper() {
        play(SoundEvents.SOUL_ESCAPE.value(), 0.55f, 1.0f);
    }

    private static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
