/**
 * PII Detection and Automatic Redaction Utility
 *
 * Scans text blocks for emails and phone numbers using robust regular expressions,
 * handles smart delimiter stripping (|, •, ,, -, etc.), and excludes date ranges
 * (e.g. "Feb 2026 - Present", "2020 - 2024") and percentages ("95%").
 */

export const ICON_PREFIX_PATTERN = '(?:[\\u2600-\\u27BF\\uE000-\\uF8FF\\uD83C-\\uDBFF\\uDC00-\\uDFFF✉☎✆📞📧📱📍]\\s*)?';

export const EMAIL_PATTERN_STR = `(?:${ICON_PREFIX_PATTERN})[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}`;
export const PHONE_PATTERN_STR = `(?:${ICON_PREFIX_PATTERN})(?:\\+?\\d{1,3}[\\s-]?)?(?:\\(?\\d{2,5}\\)?[\\s-]?)?\\d{3,5}[\\s-]?\\d{4,6}`;

export const EMAIL_REGEX = new RegExp(EMAIL_PATTERN_STR, 'g');
export const PHONE_REGEX = new RegExp(PHONE_PATTERN_STR, 'g');

const MONTH_PREFIX_REGEX = /(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s*$/i;
const DATE_SUFFIX_REGEX = /^\s*[-–—]\s*(?:present|current|now|till\s*date)/i;
const YEAR_RANGE_REGEX = /^(?:19|20)\d{2}\s*[-–—/]\s*(?:19|20)\d{2}$/;

/**
 * Validates whether a matched candidate string is a real phone number
 * and not a year, year range, date range, percentage, or other false positive.
 *
 * @param {string} matchStr - Matched substring
 * @param {string} fullText - Full string context
 * @param {number} matchIndex - Index in full string
 * @returns {boolean} True if likely a valid phone number
 */
export function isLikelyPhoneNumber(matchStr, fullText, matchIndex) {
  if (!matchStr) return false;
  const trimmed = matchStr.trim();
  const digitsOnly = trimmed.replace(/\D/g, '');

  // Valid phone numbers typically have 7 to 15 digits (E.164 max 15)
  if (digitsOnly.length < 7 || digitsOnly.length > 15) {
    return false;
  }

  // Reject year ranges like "2020 - 2024", "1998 - 2002"
  if (YEAR_RANGE_REGEX.test(trimmed)) {
    return false;
  }

  // Reject standalone 4-digit years
  if (/^(?:19|20)\d{2}$/.test(trimmed)) {
    return false;
  }

  // Reject if adjacent to percentage
  const beforeChar = fullText.slice(Math.max(0, matchIndex - 2), matchIndex);
  const afterChar = fullText.slice(matchIndex + matchStr.length, matchIndex + matchStr.length + 3);
  if (beforeChar.includes('%') || afterChar.includes('%')) {
    return false;
  }

  // Reject if preceded by month name (e.g., "Feb 2026 - Present" or "Jan 2020 - 2024")
  const textBefore = fullText.slice(Math.max(0, matchIndex - 20), matchIndex);
  if (MONTH_PREFIX_REGEX.test(textBefore)) {
    return false;
  }

  // Reject if followed by date suffix (e.g., " - Present")
  const textAfter = fullText.slice(matchIndex + matchStr.length, matchIndex + matchStr.length + 20);
  if (DATE_SUFFIX_REGEX.test(textAfter)) {
    return false;
  }

  // Reject date formats like MM/YYYY - MM/YYYY or DD/MM/YYYY
  if (/^\d{1,2}\/\d{4}/.test(trimmed) || /\/\d{4}$/.test(trimmed)) {
    return false;
  }

  return true;
}

/**
 * Finds all PII matches (emails and phone numbers) in the given text.
 * Returns array of { start, end, match, type } sorted by start index.
 *
 * @param {string} text - Text to scan
 * @returns {Array<Object>} Sorted non-overlapping PII spans
 */
export function findPIISpans(text) {
  if (!text || typeof text !== 'string') return [];
  const spans = [];

  // 1. Scan for emails
  let m;
  const emailRegex = new RegExp(EMAIL_REGEX.source, 'g');
  while ((m = emailRegex.exec(text)) !== null) {
    let start = m.index;
    const end = m.index + m[0].length;

    // Check if preceded by a contact label like "Email: " or "E-mail: " or icon glyphs
    const before = text.slice(0, start);
    const labelMatch = before.match(/(?:(?:email|e-mail)\s*[:.-]?\s*|[\u2600-\u27BF\uE000-\uF8FF\uD83C-\uDBFF\uDC00-\uDFFF✉☎✆📞📧📱📍]\s*)+$/i);
    if (labelMatch) {
      start = before.length - labelMatch[0].length;
    }

    spans.push({ start, end, match: text.slice(start, end), type: 'email' });
  }

  // 2. Scan for phone numbers
  const phoneRegex = new RegExp(PHONE_REGEX.source, 'g');
  while ((m = phoneRegex.exec(text)) !== null) {
    if (isLikelyPhoneNumber(m[0], text, m.index)) {
      let start = m.index;
      const end = m.index + m[0].length;

      // Check if preceded by a contact label like "Phone: ", "Tel: ", "Mobile: ", etc. or icon glyphs
      const before = text.slice(0, start);
      const labelMatch = before.match(/(?:(?:phone|tel|telephone|mobile|mob|cell|call|contact)\s*[:.-]?\s*|[\u2600-\u27BF\uE000-\uF8FF\uD83C-\uDBFF\uDC00-\uDFFF✉☎✆📞📧📱📍]\s*)+$/i);
      if (labelMatch) {
        start = before.length - labelMatch[0].length;
      }

      spans.push({ start, end, match: text.slice(start, end), type: 'phone' });
    }
  }

  // Sort spans by start asc
  spans.sort((a, b) => a.start - b.start);

  // Merge overlapping or adjacent spans
  const merged = [];
  for (const span of spans) {
    if (merged.length === 0) {
      merged.push(span);
      continue;
    }
    const prev = merged[merged.length - 1];
    if (span.start <= prev.end) {
      prev.end = Math.max(prev.end, span.end);
      prev.match = text.slice(prev.start, prev.end);
    } else {
      merged.push(span);
    }
  }

  return merged;
}

/**
 * Checks whether text contains any email or phone number.
 *
 * @param {string} text - Text to check
 * @returns {boolean} True if PII is detected
 */
export function hasPII(text) {
  if (!text || typeof text !== 'string') return false;
  return findPIISpans(text).length > 0;
}

/**
 * Strips PII from text and cleans up orphaned delimiters and whitespace.
 *
 * Delimiters handled: |, •, ,, -, –, —, /, ·
 *
 * @param {string} text - Input text
 * @returns {string} Cleaned text without PII or empty string if only PII was present
 */
export function redactPIIFromText(text) {
  if (!text || typeof text !== 'string') return '';

  const spans = findPIISpans(text);
  if (spans.length === 0) {
    return text;
  }

  // Cut out the PII spans
  let result = '';
  let lastIndex = 0;
  for (const span of spans) {
    result += text.slice(lastIndex, span.start);
    lastIndex = span.end;
  }
  result += text.slice(lastIndex);

  // 1. Collapse multiple repeated delimiters with optional whitespace between them
  // e.g. "| |", "| •", "• |", ", |", "| -"
  result = result.replace(/([|•,\-–—·/])\s*(?:[|•,\-–—·/]\s*)+/g, '$1 ');

  // 2. Remove leading delimiter
  // e.g. "^ | Jabalpur" -> "Jabalpur"
  result = result.replace(/^\s*[|,\-–—·/]\s*/, '');

  // 3. Remove trailing delimiter
  // e.g. "Jabalpur | " -> "Jabalpur"
  result = result.replace(/\s*[|•,\-–—·/]\s*$/, '');

  // 4. Clean up any remaining leading/trailing whitespace
  result = result.trim();

  // 5. If the entire remaining text is only whitespace or delimiters or icon glyphs, return ""
  if (!result || /^[|•,\-–—·/:\s\u2600-\u27BF\uE000-\uF8FF\uD83C-\uDBFF\uDC00-\uDFFF✉☎✆📞📧📱📍]+$/.test(result)) {
    return '';
  }

  // Also, if stripping an email/phone leaves a text block with ONLY 1-2 non-alphanumeric/PUA characters (e.g., an isolated font icon glyph), clear the entire text block to ""
  if (!/[a-zA-Z0-9]/.test(result) && result.length <= 3) {
    return '';
  }

  // 6. Clean up internal excessive whitespace
  result = result.replace(/\s{2,}/g, ' ');

  return result;
}

export default {
  ICON_PREFIX_PATTERN,
  EMAIL_REGEX,
  PHONE_REGEX,
  isLikelyPhoneNumber,
  findPIISpans,
  hasPII,
  redactPIIFromText
};
