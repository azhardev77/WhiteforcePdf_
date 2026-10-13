import Paragraph from '@tiptap/extension-paragraph';
import Heading from '@tiptap/extension-heading';
import TextStyle from '@tiptap/extension-text-style';

export const CustomParagraph = Paragraph.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      blockId: {
        default: null,
        parseHTML: (element) => element.getAttribute('data-block-id'),
        renderHTML: (attributes) => (attributes.blockId ? { 'data-block-id': attributes.blockId } : {})
      },
      bbox: {
        default: null,
        parseHTML: (element) => {
          const val = element.getAttribute('data-bbox');
          try {
            return val ? JSON.parse(val) : null;
          } catch {
            return null;
          }
        },
        renderHTML: (attributes) => (attributes.bbox ? { 'data-bbox': JSON.stringify(attributes.bbox) } : {})
      },
      pageNumber: {
        default: null,
        parseHTML: (element) => parseInt(element.getAttribute('data-page-number'), 10) || null,
        renderHTML: (attributes) => (attributes.pageNumber ? { 'data-page-number': attributes.pageNumber } : {})
      },
      pageWidth: {
        default: null
      },
      pageHeight: {
        default: null
      }
    };
  }
});

export const CustomHeading = Heading.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      blockId: {
        default: null,
        parseHTML: (element) => element.getAttribute('data-block-id'),
        renderHTML: (attributes) => (attributes.blockId ? { 'data-block-id': attributes.blockId } : {})
      },
      bbox: {
        default: null,
        parseHTML: (element) => {
          const val = element.getAttribute('data-bbox');
          try {
            return val ? JSON.parse(val) : null;
          } catch {
            return null;
          }
        },
        renderHTML: (attributes) => (attributes.bbox ? { 'data-bbox': JSON.stringify(attributes.bbox) } : {})
      },
      pageNumber: {
        default: null,
        parseHTML: (element) => parseInt(element.getAttribute('data-page-number'), 10) || null,
        renderHTML: (attributes) => (attributes.pageNumber ? { 'data-page-number': attributes.pageNumber } : {})
      },
      pageWidth: {
        default: null
      },
      pageHeight: {
        default: null
      }
    };
  }
});

export const CustomTextStyle = TextStyle.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      fontSize: {
        default: null,
        parseHTML: (element) => element.style.fontSize || null,
        renderHTML: (attributes) => {
          if (!attributes.fontSize) return {};
          return {
            style: `font-size: ${attributes.fontSize}`
          };
        }
      },
      fontFamily: {
        default: null,
        parseHTML: (element) => element.style.fontFamily || null,
        renderHTML: (attributes) => {
          if (!attributes.fontFamily) return {};
          return {
            style: `font-family: ${attributes.fontFamily}`
          };
        }
      }
    };
  }
});
