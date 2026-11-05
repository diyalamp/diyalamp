package com.diyalamp.desktop;

import javax.swing.event.UndoableEditEvent;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.undo.CompoundEdit;

/** Groups a user command's document changes into one undo step without replacing listeners. */
final class WritingDocument extends DefaultStyledDocument {
  private CompoundEdit pending;
  private boolean changed;

  void atomic(Runnable command) {
    if (pending != null) {
      command.run();
      return;
    }
    CompoundEdit group = new CompoundEdit();
    pending = group;
    changed = false;
    try {
      command.run();
    } finally {
      // Even a failed command must leave its completed mutations available to Undo.
      pending = null;
      group.end();
      if (changed) super.fireUndoableEditUpdate(new UndoableEditEvent(this, group));
    }
  }

  @Override
  protected void fireUndoableEditUpdate(UndoableEditEvent event) {
    if (pending == null) super.fireUndoableEditUpdate(event);
    else {
      pending.addEdit(event.getEdit());
      changed = true;
    }
  }
}
