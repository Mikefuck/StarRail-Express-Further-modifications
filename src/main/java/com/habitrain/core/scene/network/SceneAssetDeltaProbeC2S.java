package com.habitrain.core.scene.network;

import com.habitrain.core.HabiTrainCore;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S：询问"这张地图当前这版资产，有没有相对某个旧版本的增量补丁"。
 *
 * <p>为什么用一次探测而不是把补丁信息塞进清单包：清单包/预取包的字段布局一旦变动，旧客户端
 * 会不会容忍未读尽的尾部字节，取决于 vanilla/Fabric 的自定义包解码实现——那是不该赌的外部行为。
 * 探测包是**新增**包型：旧客户端从不发送它，也就永远收不到应答；服务端只对探测者回话。
 * 于是新旧四种组合都安全，且不需要强制所有人一起升级。</p>
 *
 * <p>探测只带 mapKey 与目标 hash，不带"我手里有哪版"——服务端回答"当前资产的补丁底是谁"，
 * 客户端再自己看本地有没有那份底。这样客户端不需要持久化任何 mapKey→hash 映射。</p>
 */
public record SceneAssetDeltaProbeC2S(String mapKey, String targetSha256) implements CustomPacketPayload {
    public static final Type<SceneAssetDeltaProbeC2S> TYPE =
            new Type<>(HabiTrainCore.id("scene_asset_delta_probe"));

    public SceneAssetDeltaProbeC2S {
        mapKey = mapKey == null ? "" : mapKey;
        targetSha256 = targetSha256 == null ? "" : targetSha256;
    }

    public static final StreamCodec<ByteBuf, SceneAssetDeltaProbeC2S> CODEC = new StreamCodec<>() {
        @Override
        public SceneAssetDeltaProbeC2S decode(ByteBuf buf) {
            String map = ScenePacketIo.readId(buf);
            String hash = ScenePacketIo.readHash(buf);
            return new SceneAssetDeltaProbeC2S(map, hash);
        }

        @Override
        public void encode(ByteBuf buf, SceneAssetDeltaProbeC2S payload) {
            ScenePacketIo.writeId(buf, payload.mapKey);
            ScenePacketIo.writeHash(buf, payload.targetSha256);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(TYPE, CODEC);
    }
}
