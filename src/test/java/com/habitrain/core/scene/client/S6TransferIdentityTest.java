package com.habitrain.core.scene.client;
import com.habitrain.core.api.scene.asset.SceneAssetDescriptor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class S6TransferIdentityTest {
    @Test void failedFullAttemptCannotHideItsActiveDeltaReplacement() {
        var registry=new AssetTransferRegistry();
        String target="a".repeat(64), patch="b".repeat(64), base="c".repeat(64);
        var old=registry.acquire(target,100,false,value->{}).entry();
        registry.completeFailure(old);
        var descriptor=new SceneAssetDescriptor(target,100,100,1,3955,"",0);
        var next=registry.acquire(patch,50,false,new AssetTransferRegistry.PatchTarget(base,descriptor,
                SceneAssetDownloadQueue.Priority.PREFETCH),value->{});
        assertSame(next.entry(),registry.find(target));
        assertSame(next.entry(),registry.acquire(target,100,false,value->{}).entry());
        assertEquals(1,registry.activeCount());
        var joined=registry.acquire(patch,50,false,new AssetTransferRegistry.PatchTarget(base,descriptor,
                SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME),value->{});
        assertSame(next.entry(),joined.entry());
        assertEquals(SceneAssetDownloadQueue.Priority.PRIMARY_RUNTIME,joined.entry().patchTarget().priority());
        assertNotSame(old,registry.find(target));
    }
}
