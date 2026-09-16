package com.habitrain.core.scene.network;

/**
 * 场景资产传输协议的版本标识。
 *
 * <p><b>它只是一个能力广告，不是拒绝开关。</b>服务端在清单包里带上自己的版本，客户端据此判断
 * "对面会不会认得某些新增能力"，从而决定要不要走那些只有新端才认识的流程（例如增量补丁探测）。
 * 版本不同**不会**导致拒绝传输：新旧端之间的既有包布局从未变过，真按"不匹配即拒绝"来做，
 * 会让"新客户端 + 旧服务端"这种过渡组合一进图就没有任何背景。</p>
 *
 * <p>版本递增规则：任何分片包 / 清单包 / 预取包的字段布局或语义变更都要 +1。</p>
 */
public final class SceneProtocol {
    /**
     * 3 = 支持增量补丁协商（客户端可发 {@link SceneAssetDeltaProbeC2S}，服务端会回
     * {@link SceneAssetDeltaOfferS2C}）。2 及更早的端不认识这两个包，因此客户端在探测前
     * 必须先确认服务端版本 ≥ 3。
     * 2 = 分片包引入 transferId / requestId 关联字段，并新增 SceneAssetChunkStatusS2C。
     * 1 = 初版（仅 sha256 关联，无状态回包）。
     */
    public static final int VERSION = 3;

    /** 能协商增量补丁的最低版本。 */
    public static final int DELTA_NEGOTIATION_VERSION = 3;

    /** 清单包缺失协议版本字段时视为初版。 */
    public static final int LEGACY_VERSION = 1;

    private SceneProtocol() {}
}
