import { Node, mergeAttributes } from '@tiptap/core';

export const PageBreak = Node.create({
  name: 'pageBreak',
  group: 'block',
  atom: true,
  selectable: true,
  draggable: false,

  addAttributes() {
    return {
      pageNumber: {
        default: 1,
        parseHTML: (element) => parseInt(element.getAttribute('data-page-number'), 10) || 1,
        renderHTML: (attributes) => ({
          'data-page-number': attributes.pageNumber
        })
      },
      width: {
        default: 612.0,
        parseHTML: (element) => parseFloat(element.getAttribute('data-page-width')) || 612.0,
        renderHTML: (attributes) => ({
          'data-page-width': attributes.width
        })
      },
      height: {
        default: 792.0,
        parseHTML: (element) => parseFloat(element.getAttribute('data-page-height')) || 792.0,
        renderHTML: (attributes) => ({
          'data-page-height': attributes.height
        })
      }
    };
  },

  parseHTML() {
    return [
      { tag: 'div[data-type="page-break"]' },
      { tag: 'div.pdf-page-separator' }
    ];
  },

  renderHTML({ node, HTMLAttributes }) {
    const pageNum = node.attrs.pageNumber || '';
    return [
      'div',
      mergeAttributes(HTMLAttributes, {
        class: 'pdf-page-separator',
        'data-type': 'page-break',
        contenteditable: 'false'
      }),
      ['div', { class: 'page-separator-line' }],
      ['span', { class: 'page-separator-badge' }, `Page ${pageNum}`],
      ['div', { class: 'page-separator-line' }]
    ];
  }
});
