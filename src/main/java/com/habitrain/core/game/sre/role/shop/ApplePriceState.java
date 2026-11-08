package com.habitrain.core.game.sre.role.shop;

import java.util.Map;
import java.util.Objects;

/** Per-slot scramble followed by an independently rolled, round-long fixed price. */
public final class ApplePriceState<K> {
    public static final int FIXED_FRAME = -1;
    public record Quote<K>(K signature, int normalPrice, int price) {}
    public record Offer<K>(Quote<K> quote, long revision, int frame) {}

    private String role = "";
    private long settlesAt;
    private long seed;
    private long secretSalt;
    private int syncedFrame = FIXED_FRAME;
    private long revision;
    private boolean settled;
    private Map<Integer, Quote<K>> quotes = Map.of();

    public void apply(String role, long settlesAt, long seed, long revision, boolean settled,
                      Map<Integer, Quote<K>> quotes) {
        apply(role, settlesAt, seed, FIXED_FRAME, revision, settled, quotes);
    }

    public void apply(String role, long settlesAt, long seed, int syncedFrame, long revision,
                      boolean settled, Map<Integer, Quote<K>> quotes) {
        applyWithSecret(role, settlesAt, seed, 0L, syncedFrame, revision, settled, quotes);
    }

    public void applyWithSecret(String role, long settlesAt, long seed, long secretSalt, long revision,
                                boolean settled, Map<Integer, Quote<K>> quotes) {
        applyWithSecret(role, settlesAt, seed, secretSalt,
                (!settled && secretSalt != 0L) ? 0 : FIXED_FRAME, revision, settled, quotes);
    }

    public void applyWithSecret(String role, long settlesAt, long seed, long secretSalt, int syncedFrame,
                                long revision, boolean settled, Map<Integer, Quote<K>> quotes) {
        this.role = Objects.requireNonNull(role);
        this.settlesAt = settlesAt;
        this.seed = seed;
        this.secretSalt = secretSalt;
        this.syncedFrame = settled ? FIXED_FRAME : syncedFrame;
        this.revision = revision;
        this.settled = settled;
        this.quotes = Map.copyOf(quotes);
    }

    public boolean advanceSyncedFrame(long now) {
        if (settled || secretSalt == 0L || isEmpty()) return false;
        int nextFrame = frame(now);
        if (nextFrame == syncedFrame) return false;
        syncedFrame = nextFrame;
        return true;
    }

    public int syncedFrame() {
        return syncedFrame;
    }

    /** Returns a one-time frame token during active server scramble so the master seed is never exposed. */
    public long clientSeed() {
        if (settled) return 0L; // fixed quotes need no RNG token; never reveal the master seed later
        return secretSalt != 0L
                ? SnakeFruitPricePolicy.frameSeed(seed, secretSalt, Math.max(0, syncedFrame))
                : seed;
    }

    public Offer<K> offer(String currentRole, int index, K signature, long now) {
        Quote<K> quote = matching(currentRole, index, signature);
        if (quote == null) return null;
        int frame = settled
                ? FIXED_FRAME
                : (secretSalt == 0L && syncedFrame >= 0 ? syncedFrame : frame(now));
        return new Offer<>(atFrame(index, quote, frame), revision, frame);
    }

    public Quote<K> quote(String currentRole, int index, K signature, long now) {
        var offer = offer(currentRole, index, signature, now);
        return offer == null ? null : offer.quote();
    }

    /** Never trust a client price. Recompute a recent, actually displayable frame instead. */
    public Quote<K> purchaseQuote(String currentRole, int index, K signature, long now,
                                  long revision, int frame) {
        Quote<K> quote = matching(currentRole, index, signature);
        if (quote == null || this.revision != revision) return null;
        if (frame == FIXED_FRAME) return settled && now >= settlesAt ? quote : null;
        if (settled || now >= settlesAt || frame < 0 || frame > frame(now)) return null;
        long frameStarted = settlesAt - SnakeFruitPricePolicy.DURATION_TICKS
                + (long) frame * SnakeFruitPricePolicy.SCRAMBLE_INTERVAL_TICKS;
        if (now - frameStarted > SnakeFruitPricePolicy.QUOTE_GRACE_TICKS) return null;
        return atFrame(index, quote, frame);
    }

    private Quote<K> matching(String currentRole, int index, K signature) {
        if (!active(currentRole)) return null;
        Quote<K> quote = quotes.get(index);
        return quote != null && Objects.equals(quote.signature(), signature) ? quote : null;
    }

    private Quote<K> atFrame(int index, Quote<K> quote, int frame) {
        if (frame == FIXED_FRAME) return quote;
        long effectiveSeed = (!settled && secretSalt != 0L)
                ? SnakeFruitPricePolicy.frameSeed(seed, secretSalt, frame)
                : seed;
        return new Quote<>(quote.signature(), quote.normalPrice(),
                SnakeFruitPricePolicy.scramblePrice(effectiveSeed, index, frame));
    }

    public int frame(long now) {
        long elapsed = now - (settlesAt - SnakeFruitPricePolicy.DURATION_TICKS);
        return (int) Math.clamp(elapsed / SnakeFruitPricePolicy.SCRAMBLE_INTERVAL_TICKS,
                0, SnakeFruitPricePolicy.DURATION_TICKS / SnakeFruitPricePolicy.SCRAMBLE_INTERVAL_TICKS - 1);
    }

    public boolean settle(long now) {
        if (isEmpty() || settled || now < settlesAt) return false;
        settled = true;
        syncedFrame = FIXED_FRAME;
        return true;
    }

    public boolean active(String currentRole) { return !quotes.isEmpty() && role.equals(currentRole); }
    public boolean isEmpty() { return quotes.isEmpty(); }
    public String role() { return role; }
    public long settlesAt() { return settlesAt; }
    public long seed() { return seed; }
    public long revision() { return revision; }
    public boolean settled() { return settled; }
    public Map<Integer, Quote<K>> quotes() { return quotes; }

    public void clear() {
        role = "";
        settlesAt = seed = secretSalt = revision = 0;
        syncedFrame = FIXED_FRAME;
        settled = false;
        quotes = Map.of();
    }
}
