package com.habitrain.core.vote;

import com.habitrain.core.api.ModeMapVoteConfig;
import com.habitrain.core.api.ModeMapVoteSnapshot;
import com.habitrain.core.api.VoteOption;
import com.habitrain.core.api.VoteResult;
import com.habitrain.core.api.spi.VoteBridge;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 审核 A2：{@link OptionVoteManager} 与 {@link ModeMapVoteOrchestrator} 的方法都是静态的，
 * 无法直接实现 SPI 接口方法，因此用这个薄适配器装配到 {@link VoteBridge}。
 */
public final class VoteBridgeImpl implements VoteBridge {

    public static final VoteBridgeImpl INSTANCE = new VoteBridgeImpl();

    private VoteBridgeImpl() {}

    @Override
    public boolean optionStart(ServerLevel level, String voteId, @Nullable String title,
                               @Nullable String description, List<VoteOption> options,
                               int durationSeconds, Consumer<VoteResult> onResolved) {
        return OptionVoteManager.start(level, voteId, title, description, options, durationSeconds, onResolved);
    }

    @Override
    public boolean optionCast(ServerLevel level, UUID voter, @Nullable String voteId, @Nullable String optionId) {
        return OptionVoteManager.cast(level, voter, voteId, optionId);
    }

    @Override
    public boolean optionIsActive(ServerLevel level) {
        return OptionVoteManager.isActive(level);
    }

    @Override
    public void optionCancel(ServerLevel level) {
        OptionVoteManager.cancel(level);
    }

    @Override
    public String optionCurrentVoteId(ServerLevel level) {
        String id = OptionVoteManager.currentVoteId(level);
        return id != null ? id : "";
    }

    @Override
    public boolean modeMapStart(ServerLevel level, ModeMapVoteConfig config) {
        return ModeMapVoteOrchestrator.start(level, config);
    }

    @Override
    public String modeMapLastFailure(ServerLevel level) {
        return ModeMapVoteOrchestrator.lastFailure(level);
    }

    @Override
    public boolean modeMapCancel(ServerLevel level) {
        return ModeMapVoteOrchestrator.cancel(level);
    }

    @Override
    public boolean modeMapIsRunning(ServerLevel level) {
        return ModeMapVoteOrchestrator.isRunning(level);
    }

    @Override
    public ModeMapVoteSnapshot modeMapSnapshot(ServerLevel level) {
        return ModeMapVoteOrchestrator.snapshot(level);
    }
}
