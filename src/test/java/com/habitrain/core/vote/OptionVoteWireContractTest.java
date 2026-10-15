package com.habitrain.core.vote;

import com.habitrain.core.api.VoteOption;
import com.habitrain.core.network.OptionVotePayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class OptionVoteWireContractTest {
    @Test void boundaryInputRoundTripsThroughActualCodec() {
        var options = IntStream.range(0, 64).mapToObj(i -> new VoteOption("id" + i, "名".repeat(64))).toList();
        String title = "题".repeat(128), description = "描".repeat(256);
        assertTrue(OptionVoteManager.hasValidWireContract("投".repeat(64), title, description, options));
        var payload = new OptionVotePayload("投".repeat(64), true, 10, 10, 1, title, description, "",
                options.stream().map(o -> new OptionVotePayload.Entry(o.id(), o.displayName(), 0)).toList());
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            OptionVotePayload.CODEC.encode(buffer, payload);
            assertEquals(payload, OptionVotePayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void rejectsEveryOversizedDimensionBeforeEncoding() {
        var one = List.of(new VoteOption("a", "A"));
        var many = IntStream.range(0, 65).mapToObj(i -> new VoteOption("id" + i, "A")).toList();
        assertFalse(OptionVoteManager.hasValidWireContract("vote", "", "", many));
        assertFalse(OptionVoteManager.hasValidWireContract("vote", "题".repeat(129), "", one));
        assertFalse(OptionVoteManager.hasValidWireContract("vote", "", "描".repeat(257), one));
        assertFalse(OptionVoteManager.hasValidWireContract("v".repeat(65), "", "", one));
        assertFalse(OptionVoteManager.hasValidOptionIds(List.of(new VoteOption("i".repeat(65), "A"))));
        assertFalse(OptionVoteManager.hasValidOptionIds(List.of(new VoteOption("a", "n".repeat(65)))));
        assertFalse(OptionVoteManager.hasValidOptionIds(List.of(new VoteOption("a", "A"), new VoteOption("a", "B"))));
        assertTrue(OptionVoteManager.hasValidWireContract("vote", null, null, one));
        assertFalse(OptionVoteManager.hasValidOptionIds(List.of()));
    }
}
