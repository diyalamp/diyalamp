package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Tab;
import atlantafx.base.controls.TabLine;

/** AtlantaFX headers reordered through its permutation API during a native drag gesture. */
final class FxDocumentTabs extends TabLine {
  FxDocumentTabs() {
    // The library's mouse-only gesture cannot become an external native drag halfway through.
    // Native DND owns the gesture; the maintained reorder API still owns the tab/skin permutation.
    setTabDragPolicy(Tab.DragPolicy.FIXED);
  }

  boolean moveToInsertion(Tab tab, int insertion) {
    int from = getTabs().indexOf(tab);
    if (from < 0) return false;
    int to = Math.max(0, Math.min(getTabs().size() - 1, insertion > from ? insertion - 1 : insertion));
    if (from != to) reorderTabs(from, to);
    getSelectionModel().select(tab);
    return true;
  }
}
