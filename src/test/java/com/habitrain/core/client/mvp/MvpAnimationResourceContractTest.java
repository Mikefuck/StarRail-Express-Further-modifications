package com.habitrain.core.client.mvp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kosmx.playerAnim.api.IPlayable;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.minecraftApi.codec.AnimationCodecs;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MvpAnimationResourceContractTest {

    @Test
    void everyBuiltInHasAResourceWhoseNameMatchesItsId() {
        for (MvpAnimationDefinition definition : MvpAnimationDefinition.BUILT_INS) {
            JsonObject resource = readResource(definition.id());
            assertEquals(definition.id(), resource.get("name").getAsString());
        }
    }

    @Test
    void everyBuiltInCanBeDecodedByTheBundledPlayerAnimator() {
        for (MvpAnimationDefinition definition : MvpAnimationDefinition.BUILT_INS) {
            try {
                Collection<IPlayable> animations = AnimationCodecs.deserialize(
                        "json", () -> openResource(definition.id()));
                assertEquals(1, animations.size(), definition.id());
                IPlayable playable = animations.iterator().next();
                assertEquals(definition.id(), playable.getName().replace("\"", ""));
                KeyframeAnimation animation = assertInstanceOf(KeyframeAnimation.class, playable);
                assertTrue(animation.isInfinite(), definition.id() + " must cover the results screen");
            } catch (Exception exception) {
                throw new AssertionError("Player Animator rejected " + definition.id(), exception);
            }
        }
    }

    @Test
    void shortVictoryActionsLoopForTheWholeResultsScreen() {
        assertTrue(isLooping("victory_bow"), "victory_bow must not end after three seconds");
        assertTrue(isLooping("victory_dab"), "victory_dab must not end after three seconds");
    }

    private static boolean isLooping(String id) {
        return readResource(id).getAsJsonObject("emote").get("isLoop").getAsBoolean();
    }

    private static JsonObject readResource(String id) {
        String path = resourcePath(id);
        InputStream stream = openResource(id);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            throw new AssertionError("Unable to read animation resource " + path, exception);
        }
    }

    private static InputStream openResource(String id) {
        String path = resourcePath(id);
        InputStream stream = MvpAnimationResourceContractTest.class.getResourceAsStream(path);
        assertNotNull(stream, "Missing animation resource " + path);
        return stream;
    }

    private static String resourcePath(String id) {
        return "/assets/habitrain_core/player_animations/" + id + ".json";
    }
}
