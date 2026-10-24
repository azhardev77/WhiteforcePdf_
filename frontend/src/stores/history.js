import { defineStore } from 'pinia';

/**
 * Command Interface:
 * {
 *   id: string,
 *   description: string,
 *   execute(): void,
 *   undo(): void,
 *   redo(): void
 * }
 */
export const useHistoryStore = defineStore('history', {
  state: () => ({
    undoStack: [], // Array<Command>, max depth 50
    redoStack: [], // Array<Command>
    maxDepth: 50
  }),

  getters: {
    canUndo: (state) => (state.undoStack?.length || 0) > 0,
    canRedo: (state) => (state.redoStack?.length || 0) > 0,
    undoCount: (state) => state.undoStack?.length || 0,
    redoCount: (state) => state.redoStack?.length || 0
  },

  actions: {
    /**
     * Executes a command and pushes it onto the undo stack.
     * Clears the redo stack.
     *
     * @param {Object} command
     */
    executeCommand(command) {
      if (!command) return;
      try {
        if (typeof command.execute === 'function') {
          command.execute();
        }
      } catch (err) {
        console.error('[HistoryStore] Error executing command:', err);
      }

      this.undoStack.push(command);
      if (this.undoStack.length > this.maxDepth) {
        this.undoStack.shift();
      }
      this.redoStack = [];
    },

    /**
     * Undoes the top command on the undo stack and moves it to redoStack.
     *
     * @returns {Object|null} Undone command or null
     */
    undo() {
      if (this.undoStack.length === 0) return null;
      const command = this.undoStack.pop();
      try {
        if (typeof command.undo === 'function') {
          command.undo();
        }
      } catch (err) {
        console.error('[HistoryStore] Error undoing command:', err);
      }
      this.redoStack.push(command);
      return command;
    },

    /**
     * Redoes the top command on the redo stack and moves it to undoStack.
     *
     * @returns {Object|null} Redone command or null
     */
    redo() {
      if (this.redoStack.length === 0) return null;
      const command = this.redoStack.pop();
      try {
        if (typeof command.redo === 'function') {
          command.redo();
        } else if (typeof command.execute === 'function') {
          command.execute();
        }
      } catch (err) {
        console.error('[HistoryStore] Error redoing command:', err);
      }
      this.undoStack.push(command);
      return command;
    },

    /**
     * Clears both undo and redo stacks.
     */
    clear() {
      this.undoStack = [];
      this.redoStack = [];
    }
  }
});

export default useHistoryStore;
