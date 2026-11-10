package com.habitrain.core.client.tailor;

import com.habitrain.core.network.DollTailorStatePayload;
import java.util.List;

public interface DollTailorRosterView {
    void habitrain$tailorRoster(List<DollTailorStatePayload.Entry> entries);
}
