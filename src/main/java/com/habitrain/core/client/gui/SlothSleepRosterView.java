package com.habitrain.core.client.gui;

import com.habitrain.core.network.SlothSleepRosterPayload;
import java.util.List;

/** Updates the existing backpack without opening a replacement screen. */
public interface SlothSleepRosterView {
    void habitrain$setSleepTargets(List<SlothSleepRosterPayload.Entry> entries);
}