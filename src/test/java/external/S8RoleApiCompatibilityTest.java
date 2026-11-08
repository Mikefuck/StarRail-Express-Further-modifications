package external;

import com.habitrain.core.api.role.v2.*;
import com.habitrain.core.api.role.v2.action.*;
import com.habitrain.core.api.role.v2.definition.RolePatch;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class S8RoleApiCompatibilityTest {
    @Test void typedBucketsUseTheExistingNumericProviderContract() {
        class Provider implements RoleForceApi {
            int received;
            public boolean queueExact(ServerPlayer p, RoleKey r) { return false; }
            public boolean queueFaction(ServerPlayer p, RoleFaction r) { return false; }
            public boolean queueRoleType(ServerPlayer p, int id) { received=id; return true; }
            public boolean isQueued(UUID p) { return false; }
            public void clear(UUID p) {}
        }
        var provider=new Provider();
        for(var type:RoleAssignmentType.values()) {
            assertTrue(provider.queueRoleType(null,type));
            assertEquals(type.id(),provider.received);
            assertEquals(type,RoleAssignmentType.fromId(type.id()).orElseThrow());
        }
        assertTrue(RoleAssignmentType.fromId(0).isEmpty());
        assertTrue(RoleAssignmentType.fromId(6).isEmpty());
    }
    @Test void builtInReasonTypesPreserveWireKeysAndCustomExtensions() {
        var result=RoleActionResult.rejectReason(RoleActionReason.COOLDOWN,"wait");
        assertEquals(RoleActionResult.reject(RoleActionResult.COOLDOWN,"wait"),result);
        assertEquals(RoleActionReason.COOLDOWN,result.knownReason().orElseThrow());
        assertTrue(RoleActionResult.reject("addon:custom_result").knownReason().isEmpty());
        assertEquals(RoleActionReason.OK,RoleActionResult.success().knownReason().orElseThrow());
        assertThrows(IllegalArgumentException.class,()->RoleActionResult.rejectReason(RoleActionReason.OK,null));
    }
    @Test void explicitActionBuildersAndLegacyFactoriesProduceTheSameSchema() {
        var key=RoleKey.of("example","role");
        var id=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("example","action");
        var current=RoleActionSpec.builder(id).role(key).direction(RoleActionDirection.S2C).build();
        var legacy=RoleActionSpec.of("example","action").role(key).direction(RoleActionDirection.S2C).build();
        assertEquals(legacy.id(),current.id());
        assertEquals(legacy.role(),current.role());
        assertEquals(legacy.direction(),current.direction());
    }
    @Test void transparentColorIsPresentAndDistinctFromAnAbsentOverride() {
        assertTrue(RolePatch.builder("example","role").innocent(RolePatch.BooleanPatch.set(true)).build().fixedColor().isEmpty());
        var transparent=RolePatch.builder("example","role").color(0).build();
        assertEquals(0,transparent.fixedColor().orElseThrow());
        assertEquals(transparent.color().color(),transparent.fixedColor().getAsInt());
    }
}
