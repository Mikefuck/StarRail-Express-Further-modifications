package com.habitrain.core.game.sre.mixin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SwiftWindPsychoSoundTest {
    @Test void soundOnlyPlaysAfterSuccessfulFrenzyAndRoleChecks() throws Exception {
        var method = method("com/habitrain/core/game/sre/mixin/SwiftWindPsychoMixin", "habitrain$refreshSkills");
        JumpInsnNode successGuard = null;
        JumpInsnNode roleGuard = null;
        int sounds = 0;
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.name.equals("getReturnValueZ")) successGuard = (JumpInsnNode) nextOpcode(call);
            if (call.name.equals("isHabiRole")) roleGuard = (JumpInsnNode) nextOpcode(call);
            if (call.name.equals("playSound")) {
                sounds++;
                assertNotNull(successGuard);
                assertNotNull(roleGuard);
                assertEquals(Opcodes.IFEQ, successGuard.getOpcode());
                assertEquals(Opcodes.IFEQ, roleGuard.getOpcode());
                assertSame(successGuard.label, roleGuard.label);
                assertTrue(method.instructions.indexOf(call) < method.instructions.indexOf(successGuard.label));
            }
        }
        assertEquals(1, sounds);
    }

    @Test void soundIsRegisteredAndPackagedAsMonoVorbis() throws Exception {
        var register = method("com/habitrain/core/HabiTrainCore", "registerMoreSounds");
        boolean registered = false;
        for (var instruction : register.instructions) {
            if (instruction instanceof FieldInsnNode field && field.name.equals("SWIFT_WIND_PSYCHO_SOUND")) {
                assertEquals("register", ((MethodInsnNode) nextOpcode(field)).name);
                registered = true;
            }
        }
        assertTrue(registered);
        var sound = json("sounds.json").getAsJsonObject("swift_wind_psycho");
        assertEquals("habitrain_core:swift_wind_psycho", sound.getAsJsonArray("sounds").get(0).getAsString());
        String subtitle = sound.get("subtitle").getAsString();
        for (String language : new String[]{"zh_cn", "en_us"}) {
            var translations = json("lang/" + language + ".json");
            assertFalse(translations.get(subtitle).getAsString().isBlank());
            String emptyShop = translations.get("message.habitrain_core.snake_fruit.empty_shop").getAsString();
            assertFalse(emptyShop.startsWith("["));
            assertFalse(translations.has("message.habitrain_core.snake_fruit.used"));
        }
        try (var stream = getClass().getResourceAsStream("/assets/habitrain_core/sounds/swift_wind_psycho.ogg")) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            assertEquals("OggS", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
            int header = 27 + Byte.toUnsignedInt(bytes[26]);
            assertEquals(1, bytes[header]);
            assertEquals("vorbis", new String(bytes, header + 1, 6, StandardCharsets.US_ASCII));
            assertEquals(1, bytes[header + 11], "Positional sound must be mono");
            assertEquals(44100, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(header + 12));
        }
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static MethodNode method(String name, String method) throws Exception {
        try (var stream = SwiftWindPsychoSoundTest.class.getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
        }
    }

    private static JsonObject json(String path) throws Exception {
        try (var stream = SwiftWindPsychoSoundTest.class.getResourceAsStream("/assets/habitrain_core/" + path)) {
            assertNotNull(stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
