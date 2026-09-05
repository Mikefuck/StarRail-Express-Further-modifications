package com.habitrain.core.scene.server;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps a bounded, sliding set of temporary chunk tickets around the next
 * sections that a scene capture will scan.
 *
 * <p>The class deliberately has no Minecraft dependencies so its ownership and
 * cleanup rules can be unit-tested without starting a server.</p>
 */
final class CaptureTicketWindow<K> implements AutoCloseable {
    interface TicketAccess<K> {
        void acquire(K key);

        void release(K key);
    }

    private final List<K> orderedSectionKeys;
    private final int maxTickets;
    private final TicketAccess<K> access;
    private final LinkedHashSet<K> heldTickets = new LinkedHashSet<>();
    private boolean closed;

    CaptureTicketWindow(List<K> orderedSectionKeys, int maxTickets, TicketAccess<K> access) {
        this.orderedSectionKeys = List.copyOf(Objects.requireNonNull(orderedSectionKeys, "orderedSectionKeys"));
        if (maxTickets <= 0) {
            throw new IllegalArgumentException("maxTickets must be positive");
        }
        this.maxTickets = maxTickets;
        this.access = Objects.requireNonNull(access, "access");
    }

    void alignToSection(int sectionIndex) {
        if (closed) {
            throw new IllegalStateException("ticket window is already closed");
        }

        int start = Math.max(0, Math.min(sectionIndex, orderedSectionKeys.size()));
        LinkedHashSet<K> desired = new LinkedHashSet<>();
        for (int i = start; i < orderedSectionKeys.size() && desired.size() < maxTickets; i++) {
            desired.add(orderedSectionKeys.get(i));
        }

        for (K held : new ArrayList<>(heldTickets)) {
            if (!desired.contains(held)) {
                access.release(held);
                heldTickets.remove(held);
            }
        }

        try {
            for (K key : desired) {
                if (!heldTickets.contains(key)) {
                    access.acquire(key);
                    heldTickets.add(key);
                }
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    int heldTicketCount() {
        return heldTickets.size();
    }

    Set<K> heldTicketsForTest() {
        return Set.copyOf(heldTickets);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException firstFailure = null;
        for (K key : new ArrayList<>(heldTickets)) {
            try {
                access.release(key);
            } catch (RuntimeException releaseFailure) {
                if (firstFailure == null) firstFailure = releaseFailure;
            }
        }
        heldTickets.clear();
        if (firstFailure != null) throw firstFailure;
    }
}
