<template>
  <div class="editor-wrapper">
    <div class="editor-scroll-container">
      <div class="editor-page-sheet" :style="sheetStyle">
        <!-- Floating page header / dimensions indicator -->
        <div class="sheet-meta-header">
          <span class="sheet-dim-tag">{{ dimensionText }}</span>
          <span class="sheet-pages-tag">{{ pageCountText }}</span>
        </div>

        <editor-content :editor="editor" class="editor-content-area" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { useEditor, EditorContent } from '@tiptap/vue-3';
import StarterKit from '@tiptap/starter-kit';
import Color from '@tiptap/extension-color';
import TextAlign from '@tiptap/extension-text-align';
import Table from '@tiptap/extension-table';
import TableRow from '@tiptap/extension-table-row';
import TableCell from '@tiptap/extension-table-cell';
import TableHeader from '@tiptap/extension-table-header';

import { PageBreak } from '@/extensions/PageBreak';
import { CustomParagraph, CustomHeading, CustomTextStyle } from '@/extensions/CustomNodes';
import { convertDocumentModelToTipTap } from '@/utils/jsonToTipTap';
import { useDocumentStore } from '@/stores/document';

const props = defineProps({
  documentModel: {
    type: Object,
    default: null
  }
});

const emit = defineEmits(['update', 'editorReady']);
const documentStore = useDocumentStore();

const activeDoc = computed(() => props.documentModel || documentStore.documentModel);

// Standard points to screen pixels conversion (96 / 72 = 1.3333)
const PT_TO_PX = 96.0 / 72.0;

const firstPage = computed(() => {
  if (activeDoc.value?.pages && activeDoc.value.pages.length > 0) {
    return activeDoc.value.pages[0];
  }
  return null;
});

const sheetStyle = computed(() => {
  const ptWidth = firstPage.value?.width || 612.0;
  const pxWidth = Math.round(ptWidth * PT_TO_PX);
  return {
    width: `${pxWidth}px`,
    maxWidth: '100%'
  };
});

const dimensionText = computed(() => {
  if (!firstPage.value) return 'US Letter (612 × 792 pt)';
  const w = Math.round(firstPage.value.width);
  const h = Math.round(firstPage.value.height);
  return `${w} × ${h} pt`;
});

const pageCountText = computed(() => {
  const count = activeDoc.value?.pages?.length || 1;
  return `${count} ${count === 1 ? 'Page' : 'Pages'}`;
});

let debounceTimer = null;
let isInternalSync = false;
let currentLoadedId = null;

function debouncedSync(json) {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    isInternalSync = true;
    emit('update', json);
    if (documentStore) {
      documentStore.updateFromTipTap(json);
    }
    // Release internal flag after microtask
    setTimeout(() => {
      isInternalSync = false;
    }, 50);
  }, 300);
}

const initialAst = convertDocumentModelToTipTap(activeDoc.value);
if (activeDoc.value?.id) {
  currentLoadedId = activeDoc.value.id;
}

const editor = useEditor({
  content: initialAst,
  extensions: [
    StarterKit.configure({
      paragraph: false,
      heading: false
    }),
    CustomParagraph,
    CustomHeading,
    CustomTextStyle,
    Color,
    TextAlign.configure({
      types: ['heading', 'paragraph']
    }),
    Table.configure({
      resizable: true
    }),
    TableRow,
    TableHeader,
    TableCell,
    PageBreak
  ],
  onCreate({ editor: instance }) {
    emit('editorReady', instance);
  },
  onUpdate({ editor: instance }) {
    const json = instance.getJSON();
    debouncedSync(json);
  }
});

// Watch for external document changes (file upload, document switch, sample load)
watch(
  () => activeDoc.value,
  (newDoc) => {
    if (!editor.value) return;

    if (!newDoc) {
      currentLoadedId = null;
      editor.value.commands.setContent({
        type: 'doc',
        content: [{ type: 'paragraph', content: [] }]
      }, false);
      return;
    }

    // Only update editor content if document identity changed externally
    if (newDoc.id !== currentLoadedId && !isInternalSync) {
      currentLoadedId = newDoc.id;
      const ast = convertDocumentModelToTipTap(newDoc);
      editor.value.commands.setContent(ast, false);
    }
  },
  { deep: false }
);

onBeforeUnmount(() => {
  if (debounceTimer) {
    clearTimeout(debounceTimer);
  }
  if (editor.value) {
    editor.value.destroy();
  }
});

defineExpose({
  editor
});
</script>

<style scoped>
.editor-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-color: var(--color-bg-workspace);
}

.editor-scroll-container {
  flex: 1;
  overflow-y: auto;
  overflow-x: auto;
  padding: 40px 20px 80px 20px;
  display: flex;
  justify-content: center;
}

.editor-page-sheet {
  background-color: var(--color-bg-surface);
  min-height: 1056px; /* standard 792pt * 1.3333 */
  padding: 56px 64px;
  box-shadow: var(--shadow-sheet);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  position: relative;
  transition: width 0.2s ease;
}

.sheet-meta-header {
  position: absolute;
  top: 14px;
  right: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--color-text-muted);
  user-select: none;
  pointer-events: none;
}

.sheet-dim-tag,
.sheet-pages-tag {
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-border);
  padding: 2px 8px;
  border-radius: 4px;
}

.editor-content-area {
  min-height: 800px;
}
</style>
