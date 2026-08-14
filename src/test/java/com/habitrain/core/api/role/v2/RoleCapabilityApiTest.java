package com.habitrain.core.api.role.v2;

import com.habitrain.core.api.role.v2.capability.ChatDecision;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityApi;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityContext;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityKey;
import com.habitrain.core.api.role.v2.capability.RoleCapabilityStatus;
import com.habitrain.core.api.role.v2.capability.RoleChatPolicy;
import com.habitrain.core.api.role.v2.capability.RoleVoicePolicy;
import com.habitrain.core.api.role.v2.capability.VoiceDecision;
import com.habitrain.core.role.capability.CapabilityPolicyEvaluator;
import com.habitrain.core.role.capability.RoleCapabilityServiceImpl;
import com.habitrain.core.role.diag.RoleDiagnosticsCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the v2 capability platform: policy builders, evaluator fold,
 * adapter status (missing mod stays UNAVAILABLE), freeze, and the
 * {@code /habitrain roleapi capabilities} formatter. No voicechat types.
 */
class RoleCapabilityApiTest {

    private static final RoleKey SLOTH = RoleKey.of("habitrain_core", "sin_sloth");
    private static final RoleKey TAOTIE = RoleKey.of("habi_role_port", "taotie");
    private static final RoleKey CIVILIAN = RoleKey.of("sre", "civilian");
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private RoleCapabilityServiceImpl store;

    @BeforeEach
    void setUp() {
        store = new RoleCapabilityServiceImpl();
        ((RoleCapabilityServiceImpl) RoleCapabilityApi.instance()).clear();
    }

    @AfterEach
    void tearDown() {
        ((RoleCapabilityServiceImpl) RoleCapabilityApi.instance()).clear();
    }

    @Test
    void voiceBuilderRequiresRole() {
        assertThrows(IllegalStateException.class,
                () -> RoleVoicePolicy.of("habitrain_core", "mute").muteSend().build());
    }

    @Test
    void missingAdapterStaysUnavailable() {
        assertEquals(RoleCapabilityStatus.UNAVAILABLE, store.status(RoleCapabilityKey.VOICE));
        assertFalse(store.supports(RoleCapabilityKey.VOICE));
    }

    @Test
    void bindAdapterMarksAvailableWithoutLoadingExternalClass() {
        store.bindAdapter(RoleCapabilityKey.VOICE, RoleCapabilityStatus.AVAILABLE);
        assertTrue(store.supports(RoleCapabilityKey.VOICE));
        assertEquals(RoleCapabilityStatus.AVAILABLE, store.status(RoleCapabilityKey.VOICE));
    }

    @Test
    void muteSendBlocksSpeaker() {
        store.voice(RoleVoicePolicy.of("habitrain_core", "sloth_sleep")
                .role(SLOTH).muteSend().build());
        VoiceDecision d = store.evaluateVoice(RoleCapabilityContext.of(A, SLOTH, B, CIVILIAN));
        assertEquals(VoiceDecision.BLOCK, d);
    }

    @Test
    void muteSendDoesNotBlockOtherRoles() {
        store.voice(RoleVoicePolicy.of("habitrain_core", "sloth_sleep")
                .role(SLOTH).muteSend().build());
        VoiceDecision d = store.evaluateVoice(RoleCapabilityContext.of(A, CIVILIAN, B, SLOTH));
        assertEquals(VoiceDecision.PASS, d);
    }

    @Test
    void isolatedListenerWithoutHearWorldBlocksOutsideGroup() {
        store.voice(RoleVoicePolicy.of("habi_role_port", "swallowed")
                .role(TAOTIE).isolateGroup().hearWorld(false).build());
        RoleCapabilityContext outside = RoleCapabilityContext.of(A, CIVILIAN, B, TAOTIE)
                .withGroups(null, OWNER);
        assertEquals(VoiceDecision.BLOCK, store.evaluateVoice(outside));
        RoleCapabilityContext inside = RoleCapabilityContext.of(A, CIVILIAN, B, TAOTIE)
                .withGroups(OWNER, OWNER);
        assertEquals(VoiceDecision.PASS, store.evaluateVoice(inside));
    }

    @Test
    void chatMuteSendBlocks() {
        store.chat(RoleChatPolicy.of("habitrain_core", "silence")
                .role(SLOTH).muteSend().build());
        assertEquals(ChatDecision.BLOCK,
                store.evaluateChat(RoleCapabilityContext.of(A, SLOTH, B, CIVILIAN)));
        assertEquals(ChatDecision.PASS,
                store.evaluateChat(RoleCapabilityContext.of(A, CIVILIAN, B, SLOTH)));
    }

    @Test
    void evaluatorBlockStillWinsWithIsolatePolicy() {
        RoleVoicePolicy isolate = RoleVoicePolicy.of("habi_role_port", "iso")
                .role(TAOTIE).isolateGroup().build();
        RoleVoicePolicy mute = RoleVoicePolicy.of("habitrain_core", "mute")
                .role(TAOTIE).muteSend().build();
        RoleCapabilityContext ctx = RoleCapabilityContext.of(A, TAOTIE, B, CIVILIAN)
                .withGroups(OWNER, null);
        assertEquals(VoiceDecision.BLOCK,
                CapabilityPolicyEvaluator.voice(List.of(isolate, mute), ctx));
    }

    @Test
    void freezeRejectsFurtherVoice() {
        store.voice(RoleVoicePolicy.of("habitrain_core", "a").role(SLOTH).build());
        store.freeze();
        assertThrows(IllegalStateException.class,
                () -> store.voice(RoleVoicePolicy.of("habitrain_core", "b").role(SLOTH).build()));
    }

    @Test
    void duplicateIdRejected() {
        store.voice(RoleVoicePolicy.of("habitrain_core", "a").role(SLOTH).build());
        assertThrows(IllegalArgumentException.class,
                () -> store.voice(RoleVoicePolicy.of("habitrain_core", "a").role(SLOTH).muteSend().build()));
    }

    @Test
    void capabilitiesCommandListsStatus() {
        RoleCapabilityApi.instance().bindAdapter(RoleCapabilityKey.CHAT, RoleCapabilityStatus.AVAILABLE);
        RoleCapabilityApi.instance().chat(RoleChatPolicy.of("habitrain_core", "silence")
                .role(SLOTH).muteSend().build());
        List<String> lines = RoleDiagnosticsCommands.capabilities();
        assertEquals("capabilities", lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.contains("chat=AVAILABLE")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("voice=UNAVAILABLE")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("habitrain_core:silence")));
    }
}
