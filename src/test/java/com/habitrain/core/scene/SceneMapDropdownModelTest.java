package com.habitrain.core.scene;

import com.habitrain.core.scene.model.SceneMapDropdownModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SceneMapDropdownModelTest {
    private static final List<SceneMapDropdownModel.Entry> ENTRIES = List.of(
            new SceneMapDropdownModel.Entry("__default__", "默认回退配置", true, false, false),
            new SceneMapDropdownModel.Entry("map1", "地图一", false, true, false),
            new SceneMapDropdownModel.Entry("night_line", "夜间线", true, false, true)
    );

    @Test
    void filtersByFriendlyNameAndRealMapIdWithoutReordering() {
        assertEquals(List.of("map1"), SceneMapDropdownModel.filter(ENTRIES, "地图一")
                .stream().map(SceneMapDropdownModel.Entry::key).toList());
        assertEquals(List.of("night_line"), SceneMapDropdownModel.filter(ENTRIES, "NIGHT_")
                .stream().map(SceneMapDropdownModel.Entry::key).toList());
        assertEquals(List.of("__default__", "map1", "night_line"),
                SceneMapDropdownModel.filter(ENTRIES, "").stream()
                        .map(SceneMapDropdownModel.Entry::key).toList());
    }

    @Test
    void keyboardSelectionWrapsAndKeepsTheHighlightVisible() {
        assertEquals(0, SceneMapDropdownModel.moveIndex(2, 1, 3));
        assertEquals(2, SceneMapDropdownModel.moveIndex(0, -1, 3));
        assertEquals(4, SceneMapDropdownModel.keepVisible(0, 7, 4, 10));
        assertEquals(2, SceneMapDropdownModel.keepVisible(5, 2, 4, 10));
        assertEquals(6, SceneMapDropdownModel.keepVisible(99, 9, 4, 10));
    }
}
