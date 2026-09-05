package com.habitrain.core.scene.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneFabricModelCollectorTest {
    @Test
    void recognizesSodiumPrivateContextCastFailure() {
        ClassCastException cast = new ClassCastException(
                "class com.habitrain.core.scene.client.SceneFabricModelCollector$CollectorContext "
                        + "cannot be cast to class "
                        + "net.caffeinemc.mods.sodium.client.render.frapi.render.AbstractBlockRenderContext");

        assertTrue(SceneFabricModelCollector.isRendererContextMismatch(cast));
        assertTrue(SceneFabricModelCollector.isRendererContextMismatch(
                new IllegalStateException("wrapper", cast)));
    }

    @Test
    void doesNotHideUnrelatedModelFailures() {
        assertFalse(SceneFabricModelCollector.isRendererContextMismatch(
                new ClassCastException("third.party.Model cannot be cast to ExpectedModel")));
        assertFalse(SceneFabricModelCollector.isRendererContextMismatch(
                new IllegalArgumentException("bad quad")));
    }

    @Test
    void routesSodiumAndIndiumThroughTheirOwnedRenderContext() {
        assertTrue(SceneFabricModelCollector.requiresRendererOwnedContext(
                "net.caffeinemc.mods.sodium.client.render.frapi.SodiumRenderer"));
        assertTrue(SceneFabricModelCollector.requiresRendererOwnedContext(
                "link.infra.indium.renderer.IndiumRenderer"));
        assertFalse(SceneFabricModelCollector.requiresRendererOwnedContext(
                "net.fabricmc.fabric.impl.client.indigo.renderer.IndigoRenderer"));
    }
}
