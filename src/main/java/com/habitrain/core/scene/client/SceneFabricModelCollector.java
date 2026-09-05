package com.habitrain.core.scene.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshBuilder;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Executes an enhanced {@link FabricBakedModel} and exposes the quads it actually emits.
 *
 * <p>The public Fabric API does not expose a renderer-owned block render context factory, so
 * the scene baker supplies a small compatible context backed by the active renderer's
 * {@link MeshBuilder}. It deliberately supports the parts enhanced block models are allowed to
 * use: dynamic emitters, meshes, transform stacks, cull tests, and nested vanilla models.</p>
 */
@Environment(EnvType.CLIENT)
final class SceneFabricModelCollector {
    private static final Direction[] DIRECTIONS = Direction.values();

    private SceneFabricModelCollector() {}

    static boolean isEnhanced(BakedModel model) {
        return model instanceof FabricBakedModel fabricModel && !fabricModel.isVanillaAdapter();
    }

    /**
     * @param rendererContextMismatch true when the active renderer rejected the public
     *                                {@link RenderContext} implementation. The caller must then
     *                                use {@code BlockRenderDispatcher#renderBatched} so the active
     *                                renderer can create and own its native context.
     */
    record CollectionResult(int quadCount, boolean rendererContextMismatch) {}

    static CollectionResult collect(BakedModel model,
                                    BlockAndTintGetter blockView,
                                    BlockState state,
                                    BlockPos pos,
                                    Supplier<RandomSource> randomSupplier,
                                    Predicate<Direction> faceCullTest,
                                    Consumer<QuadView> output) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(blockView, "blockView");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(randomSupplier, "randomSupplier");
        Objects.requireNonNull(output, "output");

        if (!(model instanceof FabricBakedModel fabricModel) || fabricModel.isVanillaAdapter()) {
            throw new IllegalArgumentException("Scene Fabric collector requires an enhanced FabricBakedModel");
        }

        Renderer renderer = RendererAccess.INSTANCE.getRenderer();
        if (renderer == null) {
            throw new IllegalStateException("Fabric Renderer API has no active renderer");
        }

        // Sodium/Indium enhanced models cast RenderContext to their private chunk context.
        // Entering the model with our public collector can fail after partial emission, leaving
        // an incomplete (often single-plane) scene mesh. Use the renderer-owned renderBatched
        // path up front, matching Wathe's normal Sodium terrain rendering path.
        if (requiresRendererOwnedContext(renderer.getClass().getName())) {
            return new CollectionResult(0, true);
        }

        Predicate<Direction> resolvedCullTest = faceCullTest != null
                ? faceCullTest
                : ignored -> false;
        CollectorContext context = new CollectorContext(
                renderer, state, randomSupplier,
                resolvedCullTest
        );
        try {
            fabricModel.emitBlockQuads(blockView, state, pos, randomSupplier, context);
            context.verifyBalancedTransforms();
            return collectMesh(context.build(), output);
        } catch (RuntimeException failure) {
            if (!isRendererContextMismatch(failure)) throw failure;

            // Sodium/Indium owns a private render context and rejects a standalone public API
            // implementation. Do not approximate the enhanced model with model.getQuads(): that
            // loses renderer-owned model/material passes and was observed to leave only part of
            // several Wathe models. The caller will retry through renderBatched, the same path
            // used before the scene compatibility scanner was introduced.
            return new CollectionResult(0, true);
        }
    }

    private static CollectionResult collectMesh(Mesh mesh, Consumer<QuadView> output) {
        int[] count = {0};
        mesh.forEach(quad -> {
            output.accept(quad);
            count[0]++;
        });
        return new CollectionResult(count[0], false);
    }

    static boolean isRendererContextMismatch(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (!(current instanceof ClassCastException)) continue;
            String message = current.getMessage();
            if (message != null
                    && message.contains("SceneFabricModelCollector$CollectorContext")
                    && message.contains("AbstractBlockRenderContext")) {
                return true;
            }
        }
        return false;
    }

    static boolean requiresRendererOwnedContext(String rendererClassName) {
        if (rendererClassName == null) return false;
        String normalized = rendererClassName.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("sodium") || normalized.contains("indium");
    }

    @SuppressWarnings("removal")
    private static final class CollectorContext implements RenderContext {
        private final Renderer renderer;
        private final MeshBuilder meshBuilder;
        private final BlockState renderedState;
        private final Supplier<RandomSource> randomSupplier;
        private final Predicate<Direction> faceCullTest;
        private final List<QuadTransform> transforms = new ArrayList<>();
        private final BakedModelConsumer bakedModelConsumer = new NestedModelConsumer();
        private QuadEmitter delegate;
        private final QuadEmitter proxy;

        private CollectorContext(Renderer renderer,
                                 BlockState renderedState,
                                 Supplier<RandomSource> randomSupplier,
                                 Predicate<Direction> faceCullTest) {
            this.renderer = renderer;
            this.meshBuilder = renderer.meshBuilder();
            this.renderedState = renderedState;
            this.randomSupplier = randomSupplier;
            this.faceCullTest = faceCullTest;
            this.delegate = meshBuilder.getEmitter();
            this.proxy = (QuadEmitter) Proxy.newProxyInstance(
                    QuadEmitter.class.getClassLoader(),
                    new Class<?>[]{QuadEmitter.class},
                    this::invokeEmitter
            );
        }

        @Override
        public QuadEmitter getEmitter() {
            delegate = meshBuilder.getEmitter();
            return proxy;
        }

        @Override
        public void pushTransform(QuadTransform transform) {
            transforms.add(Objects.requireNonNull(transform, "transform"));
        }

        @Override
        public void popTransform() {
            if (transforms.isEmpty()) {
                throw new IllegalStateException("Fabric model popped an empty scene transform stack");
            }
            transforms.remove(transforms.size() - 1);
        }

        @Override
        public boolean hasTransform() {
            return !transforms.isEmpty();
        }

        @Override
        public boolean isFaceCulled(Direction face) {
            // A pending transform may move the quad to a different face. Match the Fabric
            // contract by deferring culling until after all transforms have run.
            return face != null && transforms.isEmpty() && faceCullTest.test(face);
        }

        @Override
        @SuppressWarnings("removal")
        public BakedModelConsumer bakedModelConsumer() {
            return bakedModelConsumer;
        }

        private Object invokeEmitter(Object ignoredProxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "SceneFabricQuadEmitter[" + delegate + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && args.length == 1 && proxy == args[0];
                    default -> method.invoke(this, args);
                };
            }

            if (method.getName().equals("emit") && method.getParameterCount() == 0) {
                MutableQuadView quad = delegate;
                boolean accepted = true;
                for (int index = transforms.size() - 1; index >= 0; index--) {
                    if (!transforms.get(index).transform(quad)) {
                        accepted = false;
                        break;
                    }
                }
                Direction transformedCullFace = quad.cullFace();
                if (accepted && (transformedCullFace == null || !faceCullTest.test(transformedCullFace))) {
                    delegate.emit();
                } else {
                    // Asking the builder for its emitter invalidates and resets the rejected quad.
                    delegate = meshBuilder.getEmitter();
                }
                return proxy;
            }

            QuadEmitter before = delegate;
            try {
                Object returned = method.invoke(before, args);
                return returned == before ? proxy : returned;
            } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
            }
        }

        private void emitVanillaModel(BakedModel model, BlockState state) {
            if (model == null) return;
            RenderMaterial material = renderer.materialFinder().blendMode(BlendMode.DEFAULT).find();
            for (Direction direction : DIRECTIONS) {
                if (isFaceCulled(direction)) continue;
                List<?> quads = model.getQuads(state, direction, randomSupplier.get());
                if (quads == null) continue;
                for (Object value : quads) {
                    net.minecraft.client.renderer.block.model.BakedQuad quad =
                            (net.minecraft.client.renderer.block.model.BakedQuad) value;
                    getEmitter().fromVanilla(quad, material, direction).emit();
                }
            }
            List<?> unculled = model.getQuads(state, null, randomSupplier.get());
            if (unculled == null) return;
            for (Object value : unculled) {
                net.minecraft.client.renderer.block.model.BakedQuad quad =
                        (net.minecraft.client.renderer.block.model.BakedQuad) value;
                getEmitter().fromVanilla(quad, material, null).emit();
            }
        }

        private Mesh build() {
            return meshBuilder.build();
        }

        private void verifyBalancedTransforms() {
            if (!transforms.isEmpty()) {
                throw new IllegalStateException("Fabric model left " + transforms.size()
                        + " scene transform(s) on the stack");
            }
        }

        @SuppressWarnings("removal")
        private final class NestedModelConsumer implements BakedModelConsumer {
            @Override
            public void accept(BakedModel model) {
                emitVanillaModel(model, renderedState);
            }

            @Override
            public void accept(BakedModel model, BlockState state) {
                emitVanillaModel(model, state);
            }
        }
    }
}
