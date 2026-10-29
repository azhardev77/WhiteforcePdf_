<template>
  <div class="home-container">
    <!-- Header Bar -->
    <header class="home-header">
      <div class="header-brand" @click="navigateTo('/')">
        <div class="brand-icon-wrapper">
          <svg class="brand-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="16" y1="13" x2="8" y2="13" />
            <line x1="16" y1="17" x2="8" y2="17" />
            <polyline points="10 9 9 9 8 9" />
          </svg>
        </div>
        <div class="brand-text">
          <span class="brand-title">PDF Visual Studio</span>
          <span class="brand-badge">NextGen Engine</span>
        </div>
      </div>

      <div class="header-nav">
        <router-link to="/editor" class="nav-link-secondary" title="Open the standalone legacy canvas editor">
          <svg class="icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
          </svg>
          <span>Legacy Editor</span>
        </router-link>
        <a href="#api-access-section" class="nav-link-api" @click="scrollToApi">
          <span class="api-status-dot" />
          <span>API Docs</span>
        </a>
      </div>
    </header>

    <!-- Error Alert Banner -->
    <transition name="fade">
      <div v-if="errorMessage" class="error-banner" data-testid="error-banner">
        <div class="error-content">
          <svg class="error-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
          <span>{{ errorMessage }}</span>
        </div>
        <button type="button" class="btn-close-error" @click="errorMessage = ''">&times;</button>
      </div>
    </transition>

    <!-- Loading Modal Overlay -->
    <div v-if="isProcessing" class="loading-overlay" data-testid="upload-loading-overlay">
      <div class="loading-card">
        <div class="spinner-large" />
        <h3 class="loading-title">{{ loadingTitle }}</h3>
        <p class="loading-subtext">{{ loadingSubtext }}</p>
      </div>
    </div>

    <!-- Redaction Categories Selection Dialog (Resume Mode) -->
    <transition name="fade">
      <div
        v-if="showRedactDialog"
        class="modal-backdrop"
        data-testid="redact-categories-modal"
        @click.self="cancelRedactDialog"
      >
        <div class="modal-card redact-dialog-card">
          <div class="modal-header">
            <div class="modal-header-icon">
              <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
              </svg>
            </div>
            <div>
              <h3 class="modal-title">Choose What to Redact</h3>
              <p class="modal-subtitle">Select which sensitive categories to strip from the resume before opening.</p>
            </div>
            <button type="button" class="btn-close-modal" @click="cancelRedactDialog">&times;</button>
          </div>

          <div class="modal-body">
            <div v-if="isLoadingCategories" class="categories-loading">
              <div class="spinner-large" style="width: 24px; height: 24px;" />
              <span>Loading categories...</span>
            </div>
            <div v-else class="category-toggle-list">
              <label
                v-for="cat in availableCategories"
                :key="cat.id"
                class="category-toggle-item"
                :class="{ 'is-active': categoryToggles[cat.id] }"
              >
                <div class="category-info">
                  <span class="category-label">{{ cat.label }}</span>
                  <span class="category-id">{{ cat.id }}</span>
                </div>
                <div class="toggle-switch">
                  <input
                    type="checkbox"
                    v-model="categoryToggles[cat.id]"
                    :id="'toggle-' + cat.id"
                    :data-testid="'toggle-' + cat.id"
                  />
                  <span class="toggle-slider" />
                </div>
              </label>
            </div>
          </div>

          <div class="modal-footer">
            <button type="button" class="btn-secondary" @click="cancelRedactDialog">Cancel</button>
            <button
              type="button"
              class="btn-primary btn-confirm-redact"
              data-testid="btn-confirm-redact"
              @click="confirmRedactAndUpload"
            >
              Continue to Editor
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- Hidden File Input for Triggering Uploads -->
    <input
      ref="fileInputRef"
      type="file"
      accept="application/pdf"
      style="display: none"
      @change="handleFileInputChange"
    />

    <!-- Main Content Area -->
    <main class="home-main">
      <!-- MODE-SPECIFIC WORKSPACE VIEW (When route is /resume, /tender, or /edit) -->
      <section v-if="activeMode && activeMode !== 'HOME'" class="mode-workspace-view">
        <div class="mode-workspace-header">
          <button type="button" class="btn-back-overview" @click="navigateTo('/')">
            <svg class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="19" y1="12" x2="5" y2="12" />
              <polyline points="12 19 5 12 12 5" />
            </svg>
            <span>Back to All Modes</span>
          </button>
          <div class="mode-tag-pill" :class="`pill-${activeMode.toLowerCase()}`">
            {{ activeMode }} MODE
          </div>
        </div>

        <div class="mode-workspace-card">
          <div class="mode-icon-circle" :class="`circle-${activeMode.toLowerCase()}`">
            <svg v-if="activeMode === 'RESUME'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
              <line x1="9" y1="12" x2="15" y2="12" />
            </svg>
            <svg v-else-if="activeMode === 'TENDER'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
              <path d="M16 13H8" />
              <path d="M16 17H8" />
              <path d="M10 9H8" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
            </svg>
          </div>

          <h1 class="mode-title">{{ modeConfig[activeMode].title }}</h1>
          <p class="mode-description">{{ modeConfig[activeMode].description }}</p>

          <div
            class="mode-dropzone"
            :class="{ 'is-dragging': isDragging }"
            @dragover.prevent="isDragging = true"
            @dragleave.prevent="isDragging = false"
            @drop.prevent="handleFileDrop"
          >
            <svg class="dropzone-cloud-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="17 8 12 3 7 8" />
              <line x1="12" y1="3" x2="12" y2="15" />
            </svg>

            <h3 class="dropzone-text">Drag and drop your PDF here</h3>
            <p class="dropzone-sub">Supports single PDF up to 50MB</p>

            <div class="dropzone-cta-row">
              <button
                type="button"
                class="btn-primary-upload"
                :class="`btn-${activeMode.toLowerCase()}`"
                :data-testid="`btn-upload-${activeMode.toLowerCase()}`"
                @click="triggerFileBrowse(activeMode)"
              >
                <svg class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="17 8 12 3 7 8" />
                  <line x1="12" y1="3" x2="12" y2="15" />
                </svg>
                <span>Browse Computer</span>
              </button>

              <span class="or-divider">or</span>

              <button
                type="button"
                class="btn-sample-action"
                :data-testid="`btn-sample-${activeMode.toLowerCase()}`"
                @click="loadSampleForMode(activeMode)"
              >
                <span>Load Sample PDF</span>
              </button>
            </div>
          </div>

          <!-- Feature highlights list -->
          <div class="mode-highlights">
            <div v-for="(feat, idx) in modeConfig[activeMode].features" :key="idx" class="highlight-item">
              <svg class="check-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              <span>{{ feat }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- HOME OVERVIEW VIEW (When route is /) -->
      <section v-else class="home-overview">
        <!-- Hero Header -->
        <div class="hero-section">
          <div class="hero-pill">
            <span class="sparkle-dot" />
            <span>Modern Session-Based PDF Processing</span>
          </div>
          <h1 class="hero-title">
            Intelligent PDF Editing &<br />
            <span class="gradient-text">Automated Redaction Engine</span>
          </h1>
          <p class="hero-subtitle">
            Choose a mode below to launch the pixel-perfect canvas editor. Every session is securely processed with zero layout distortion, vector preservation, and in-place export.
          </p>
        </div>

        <!-- Three Main Action Buttons/Cards -->
        <div class="modes-grid">
          <!-- 1. RESUME CARD -->
          <div
            class="mode-card card-resume"
            data-testid="card-mode-resume"
            @dragover.prevent="cardDragOver('RESUME')"
            @dragleave.prevent="cardDragLeave('RESUME')"
            @drop.prevent="handleCardDrop($event, 'RESUME')"
          >
            <div class="card-glow" />
            <div class="card-header">
              <div class="card-icon-box box-resume">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
                  <line x1="9" y1="12" x2="15" y2="12" />
                </svg>
              </div>
              <span class="badge-feature">PII Protection</span>
            </div>

            <div class="card-body">
              <h2 class="card-title">Resume</h2>
              <p class="card-description">
                Automatically detects and strips emails, phone numbers, and contact links server-side before opening in the visual editor.
              </p>
              <div class="card-specs">
                <span class="spec-tag">Auto Email Redaction</span>
                <span class="spec-tag">Auto Phone Stripping</span>
                <span class="spec-tag">Scrub Annotations</span>
              </div>
            </div>

            <div class="card-footer">
              <button
                type="button"
                class="btn-mode-primary btn-resume-main"
                data-testid="btn-resume-mode"
                @click="navigateTo('/resume')"
              >
                <span>Open Resume Mode</span>
                <svg class="arrow-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="5" y1="12" x2="19" y2="12" />
                  <polyline points="12 5 19 12 12 19" />
                </svg>
              </button>
              <button
                type="button"
                class="btn-mode-sample"
                data-testid="btn-home-sample-resume"
                @click="loadSampleForMode('RESUME')"
              >
                Try Sample Resume
              </button>
            </div>
          </div>

          <!-- 2. TENDER CARD -->
          <div
            class="mode-card card-tender"
            data-testid="card-mode-tender"
            @dragover.prevent="cardDragOver('TENDER')"
            @dragleave.prevent="cardDragLeave('TENDER')"
            @drop.prevent="handleCardDrop($event, 'TENDER')"
          >
            <div class="card-glow" />
            <div class="card-header">
              <div class="card-icon-box box-tender">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <polyline points="14 2 14 8 20 8" />
                  <path d="M16 13H8" />
                  <path d="M16 17H8" />
                  <path d="M10 9H8" />
                </svg>
              </div>
              <span class="badge-feature">Procurement</span>
            </div>

            <div class="card-body">
              <h2 class="card-title">Tender</h2>
              <p class="card-description">
                Process government RFQs, commercial bids, and procurement PDFs with intact vector tables, stamps, and layout fidelity.
              </p>
              <div class="card-specs">
                <span class="spec-tag">Vector Tables</span>
                <span class="spec-tag">Clause Paragraphs</span>
                <span class="spec-tag">Empty Rule Baseline</span>
              </div>
            </div>

            <div class="card-footer">
              <button
                type="button"
                class="btn-mode-primary btn-tender-main"
                data-testid="btn-tender-mode"
                @click="navigateTo('/tender')"
              >
                <span>Open Tender Mode</span>
                <svg class="arrow-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="5" y1="12" x2="19" y2="12" />
                  <polyline points="12 5 19 12 12 19" />
                </svg>
              </button>
              <button
                type="button"
                class="btn-mode-sample"
                data-testid="btn-home-sample-tender"
                @click="loadSampleForMode('TENDER')"
              >
                Try Sample Tender
              </button>
            </div>
          </div>

          <!-- 3. EDIT PDF CARD -->
          <div
            class="mode-card card-edit"
            data-testid="card-mode-edit"
            @dragover.prevent="cardDragOver('EDIT')"
            @dragleave.prevent="cardDragLeave('EDIT')"
            @drop.prevent="handleCardDrop($event, 'EDIT')"
          >
            <div class="card-glow" />
            <div class="card-header">
              <div class="card-icon-box box-edit">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
                </svg>
              </div>
              <span class="badge-feature">Visual Canvas</span>
            </div>

            <div class="card-body">
              <h2 class="card-title">Edit PDF</h2>
              <p class="card-description">
                Direct pixel-perfect inline editing with automatic font measurement, text reflow, multi-column blocks, and PDFBox export.
              </p>
              <div class="card-specs">
                <span class="spec-tag">1:1 PDF.js Canvas</span>
                <span class="spec-tag">WPS Text Reflow</span>
                <span class="spec-tag">In-Place Overwrite</span>
              </div>
            </div>

            <div class="card-footer">
              <button
                type="button"
                class="btn-mode-primary btn-edit-main"
                data-testid="btn-edit-mode"
                @click="navigateTo('/edit')"
              >
                <span>Open Visual Editor</span>
                <svg class="arrow-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="5" y1="12" x2="19" y2="12" />
                  <polyline points="12 5 19 12 12 19" />
                </svg>
              </button>
              <button
                type="button"
                class="btn-mode-sample"
                data-testid="btn-home-sample-edit"
                @click="loadSampleForMode('EDIT')"
              >
                Try Sample PDF
              </button>
            </div>
          </div>
        </div>

        <!-- SECONDARY API ACCESS CARD (Docs + Sample cURL) -->
        <div id="api-access-section" class="api-card" data-testid="card-api-access">
          <div class="api-card-header">
            <div class="api-title-group">
              <div class="api-badge">Developer API</div>
              <h3 class="api-heading">Automate via REST API</h3>
            </div>
            <p class="api-lead">
              Integrate external workflows using the session endpoints. Pass your <code class="code-inline">X-API-KEY</code> to create sessions, obtain an <code class="code-inline">editUrl</code>, or download redacted output directly.
            </p>
          </div>

          <div class="api-endpoints-grid">
            <div class="endpoint-item">
              <div class="endpoint-badge badge-post">POST</div>
              <div class="endpoint-path">/api/v1/sessions</div>
              <div class="endpoint-desc">Upload PDF + mode -> returns { id, editUrl, expiresAt }</div>
            </div>
            <div class="endpoint-item">
              <div class="endpoint-badge badge-get">GET</div>
              <div class="endpoint-path">/api/v1/sessions/{id}/result</div>
              <div class="endpoint-desc">Stream processed PDF bytes for automated pipelines</div>
            </div>
          </div>

          <!-- Code Snippet Box -->
          <div class="code-snippet-container">
            <div class="snippet-header">
              <div class="snippet-tabs">
                <button
                  type="button"
                  class="snippet-tab"
                  :class="{ 'is-active': activeSnippetTab === 'create' }"
                  @click="activeSnippetTab = 'create'"
                >
                  1. Create Session (cURL)
                </button>
                <button
                  type="button"
                  class="snippet-tab"
                  :class="{ 'is-active': activeSnippetTab === 'result' }"
                  @click="activeSnippetTab = 'result'"
                >
                  2. Download Result (cURL)
                </button>
              </div>
              <button type="button" class="btn-copy-code" @click="copyActiveSnippet">
                <svg v-if="copiedSnippet" class="icon-xs text-green" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
                <svg v-else class="icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                  <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                </svg>
                <span>{{ copiedSnippet ? 'Copied!' : 'Copy cURL' }}</span>
              </button>
            </div>

            <pre class="snippet-body"><code>{{ activeSnippetTab === 'create' ? createSessionCurl : downloadResultCurl }}</code></pre>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { createAppSession, fetchRedactionCategories } from '@/api/sessions';

const props = defineProps({
  initialMode: {
    type: String,
    default: null
  }
});

const route = useRoute();
const router = useRouter();

const STORAGE_KEY_REDACT_TOGGLES = 'pdf_redaction_toggles';

const activeSnippetTab = ref('create');
const copiedSnippet = ref(false);
const isProcessing = ref(false);
const loadingTitle = ref('Creating Session...');
const loadingSubtext = ref('Uploading PDF and initializing canvas workspace.');
const errorMessage = ref('');
const isDragging = ref(false);
const fileInputRef = ref(null);
const pendingUploadMode = ref('EDIT');

const showRedactDialog = ref(false);
const isLoadingCategories = ref(false);
const availableCategories = ref([
  { id: 'email', label: 'Email Addresses', default: true },
  { id: 'phone', label: 'Phone Numbers', default: true }
]);
const categoryToggles = ref({
  email: true,
  phone: true
});
const pendingResumeFile = ref(null);

// Determine active mode from props or current route path
const activeMode = computed(() => {
  if (props.initialMode) return props.initialMode.toUpperCase();
  const path = route.path.toLowerCase();
  if (path === '/resume') return 'RESUME';
  if (path === '/tender') return 'TENDER';
  if (path === '/edit') return 'EDIT';
  return null;
});

const modeConfig = {
  RESUME: {
    title: 'Resume PII Redaction',
    description: 'Automated server-side redaction permanently strips email addresses, phone numbers, and interactive contact annotations before loading.',
    features: [
      'Constant-time server-side regex extraction & replacement',
      'True text-stream sanitization (no white-box masking)',
      'Scrubbing of mailto: / tel: link annotations',
      '1:1 interactive canvas review & manual adjustments'
    ],
    samplePath: '/samples/resume.pdf',
    sampleName: 'resume.pdf'
  },
  TENDER: {
    title: 'Tender & RFQ Processing',
    description: 'Designed for commercial bid packages, procurement clauses, and multi-column tender PDFs.',
    features: [
      'Preservation of vector lines, stamps, and signatures',
      'Paragraph-level block grouping and inline reflow',
      'Lossless in-place export via Apache PDFBox'
    ],
    samplePath: '/samples/tender.pdf',
    sampleName: 'tender.pdf'
  },
  EDIT: {
    title: 'Visual PDF Canvas Editor',
    description: 'High-fidelity visual canvas editor with pixel-perfect typography matching and inline text replacement.',
    features: [
      'Zero layout distortion via 1:1 PDF.js canvas rendering',
      'Interactive bounding-box drag, edit, and reflow',
      'Undo / Redo stack and direct in-place save'
    ],
    samplePath: '/samples/sample.pdf',
    sampleName: 'sample.pdf'
  }
};

const createSessionCurl = `curl -X POST http://localhost:8080/api/v1/sessions \\
  -H "X-API-KEY: YOUR_API_KEY" \\
  -F "file=@document.pdf" \\
  -F "mode=RESUME"

# Response:
# {
#   "id": "e_zL10B-u3hA12...",
#   "editUrl": "http://localhost:5173/edit/e_zL10B-u3hA12...",
#   "expiresAt": "2026-10-26T14:30:00Z"
# }`;

const downloadResultCurl = `curl -H "X-API-KEY: YOUR_API_KEY" \\
  http://localhost:8080/api/v1/sessions/{id}/result \\
  -o redacted_document.pdf`;

function navigateTo(path) {
  router.push(path);
}

function scrollToApi() {
  const el = document.getElementById('api-access-section');
  if (el) {
    el.scrollIntoView({ behavior: 'smooth' });
  }
}

function triggerFileBrowse(mode) {
  pendingUploadMode.value = mode || 'EDIT';
  if (fileInputRef.value) {
    fileInputRef.value.value = '';
    fileInputRef.value.click();
  }
}

async function openRedactDialog(file) {
  pendingResumeFile.value = file;
  isLoadingCategories.value = true;
  showRedactDialog.value = true;

  try {
    const fetched = await fetchRedactionCategories();
    if (Array.isArray(fetched) && fetched.length > 0) {
      availableCategories.value = fetched;
    }
  } catch (err) {
    console.warn('[HomeView] Could not load redaction categories, using defaults:', err);
  } finally {
    isLoadingCategories.value = false;
  }

  // Load saved state from localStorage or default all ON
  let saved = null;
  try {
    const raw = localStorage.getItem(STORAGE_KEY_REDACT_TOGGLES);
    if (raw) saved = JSON.parse(raw);
  } catch (e) {
    console.warn('[HomeView] Failed to parse saved toggle state:', e);
  }

  const newToggles = {};
  for (const cat of availableCategories.value) {
    if (saved && Object.prototype.hasOwnProperty.call(saved, cat.id)) {
      newToggles[cat.id] = Boolean(saved[cat.id]);
    } else {
      newToggles[cat.id] = cat.default !== undefined ? Boolean(cat.default) : true;
    }
  }
  categoryToggles.value = newToggles;
}

function cancelRedactDialog() {
  showRedactDialog.value = false;
  pendingResumeFile.value = null;
}

async function confirmRedactAndUpload() {
  showRedactDialog.value = false;
  const file = pendingResumeFile.value;
  pendingResumeFile.value = null;
  if (!file) return;

  // Save toggle state to localStorage
  try {
    localStorage.setItem(STORAGE_KEY_REDACT_TOGGLES, JSON.stringify(categoryToggles.value));
  } catch (e) {
    console.warn('[HomeView] Failed to save toggle state to localStorage:', e);
  }

  // Collect selected category IDs
  const selectedCategories = Object.keys(categoryToggles.value).filter(id => Boolean(categoryToggles.value[id]));
  await uploadPdfFile(file, 'RESUME', selectedCategories);
}

function startUploadFlow(file, mode = 'EDIT') {
  if (!file || !file.name.toLowerCase().endsWith('.pdf')) {
    errorMessage.value = 'Please select a valid PDF document (.pdf).';
    return;
  }
  if (mode === 'RESUME') {
    openRedactDialog(file);
  } else {
    uploadPdfFile(file, mode, null);
  }
}

function handleFileInputChange(event) {
  const files = event.target?.files;
  if (files && files.length > 0) {
    startUploadFlow(files[0], pendingUploadMode.value);
  }
}

function cardDragOver(mode) {
  // Can be used for fine-grained drag cues
}

function cardDragLeave(mode) {
  // Can be used for fine-grained drag cues
}

function handleCardDrop(event, mode) {
  const files = event.dataTransfer?.files;
  if (files && files.length > 0) {
    startUploadFlow(files[0], mode);
  }
}

function handleFileDrop(event) {
  isDragging.value = false;
  const files = event.dataTransfer?.files;
  if (files && files.length > 0) {
    startUploadFlow(files[0], activeMode.value || 'EDIT');
  }
}

async function uploadPdfFile(file, mode = 'EDIT', redactCategories = null) {
  if (!file || !file.name.toLowerCase().endsWith('.pdf')) {
    errorMessage.value = 'Please select a valid PDF document (.pdf).';
    return;
  }

  isProcessing.value = true;
  loadingTitle.value = mode === 'RESUME' ? 'Redacting PII & Initializing Session...' : 'Creating Session...';
  loadingSubtext.value = 'Uploading file to server and generating canvas workspace.';
  errorMessage.value = '';

  try {
    const result = await createAppSession(file, mode, redactCategories);
    if (!result?.id) {
      throw new Error('Failed to obtain a session ID from backend.');
    }
    await router.push(`/edit/${encodeURIComponent(result.id)}`);
  } catch (err) {
    console.error('Session creation failed:', err);
    errorMessage.value = err.response?.data?.message || err.message || 'Failed to create session.';
  } finally {
    isProcessing.value = false;
  }
}

async function loadSampleForMode(mode = 'EDIT') {
  const config = modeConfig[mode] || modeConfig.EDIT;
  isProcessing.value = true;
  loadingTitle.value = mode === 'RESUME' ? 'Loading Sample Resume...' : 'Loading Sample Document...';
  loadingSubtext.value = 'Fetching sample asset.';
  errorMessage.value = '';

  try {
    const response = await fetch(config.samplePath);
    if (!response.ok) {
      throw new Error(`Failed to load ${config.sampleName} (HTTP ${response.status})`);
    }
    const blob = await response.blob();
    const file = new File([blob], config.sampleName, { type: 'application/pdf' });
    isProcessing.value = false;
    startUploadFlow(file, mode);
  } catch (err) {
    console.error('Failed to load sample session:', err);
    errorMessage.value = err.message || 'Failed to load sample document.';
    isProcessing.value = false;
  }
}

function copyActiveSnippet() {
  const text = activeSnippetTab.value === 'create' ? createSessionCurl : downloadResultCurl;
  if (navigator?.clipboard?.writeText) {
    navigator.clipboard.writeText(text).then(() => {
      copiedSnippet.value = true;
      setTimeout(() => {
        copiedSnippet.value = false;
      }, 2500);
    });
  }
}
</script>

<style scoped>
.home-container {
  min-height: 100vh;
  width: 100%;
  background: radial-gradient(circle at 50% 0%, #1e1b4b 0%, #0b0f19 60%, #030712 100%);
  color: #f8fafc;
  display: flex;
  flex-direction: column;
  overflow-x: hidden;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
}

/* Header */
.home-header {
  height: 64px;
  padding: 0 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(11, 15, 25, 0.7);
  backdrop-filter: blur(12px);
  position: sticky;
  top: 0;
  z-index: 40;
}

.header-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  user-select: none;
}

.brand-icon-wrapper {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: linear-gradient(135deg, #6366f1, #3b82f6);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.35);
}

.brand-icon {
  width: 20px;
  height: 20px;
}

.brand-text {
  display: flex;
  align-items: center;
  gap: 8px;
}

.brand-title {
  font-size: 17px;
  font-weight: 700;
  color: #ffffff;
  letter-spacing: -0.01em;
}

.brand-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 9999px;
  background: rgba(99, 102, 241, 0.15);
  color: #818cf8;
  border: 1px solid rgba(99, 102, 241, 0.3);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.header-nav {
  display: flex;
  align-items: center;
  gap: 16px;
}

.nav-link-secondary {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #94a3b8;
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  padding: 6px 12px;
  border-radius: 6px;
  transition: all 0.15s ease;
}

.nav-link-secondary:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.06);
}

.nav-link-api {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(245, 158, 11, 0.12);
  color: #fbbf24;
  border: 1px solid rgba(245, 158, 11, 0.25);
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  transition: all 0.15s ease;
}

.nav-link-api:hover {
  background: rgba(245, 158, 11, 0.2);
  border-color: rgba(245, 158, 11, 0.4);
}

.api-status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #fbbf24;
  box-shadow: 0 0 8px #fbbf24;
}

/* Error Banner */
.error-banner {
  background: #dc2626;
  color: #ffffff;
  padding: 12px 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  font-weight: 500;
  box-shadow: 0 4px 12px rgba(220, 38, 38, 0.3);
}

.error-content {
  display: flex;
  align-items: center;
  gap: 10px;
}

.error-svg {
  width: 18px;
  height: 18px;
}

.btn-close-error {
  background: transparent;
  border: none;
  color: #ffffff;
  font-size: 20px;
  cursor: pointer;
}

/* Loading Modal */
.loading-overlay {
  position: fixed;
  inset: 0;
  background: rgba(3, 7, 18, 0.8);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}

.loading-card {
  background: #111827;
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 36px 48px;
  border-radius: 16px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.7);
}

.spinner-large {
  width: 44px;
  height: 44px;
  border: 3px solid rgba(99, 102, 241, 0.2);
  border-top-color: #6366f1;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.loading-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #ffffff;
}

.loading-subtext {
  margin: 0;
  font-size: 13px;
  color: #94a3b8;
  max-width: 320px;
}

/* Main Container */
.home-main {
  flex: 1;
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
  padding: 48px 24px 80px;
  display: flex;
  flex-direction: column;
  gap: 48px;
}

/* Hero Section */
.hero-section {
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  max-width: 800px;
  margin: 0 auto;
}

.hero-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 6px 14px;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 600;
  color: #cbd5e1;
}

.sparkle-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #38bdf8;
  box-shadow: 0 0 10px #38bdf8;
}

.hero-title {
  font-size: 44px;
  font-weight: 800;
  line-height: 1.15;
  letter-spacing: -0.03em;
  margin: 0;
}

.gradient-text {
  background: linear-gradient(135deg, #a5b4fc 0%, #38bdf8 50%, #34d399 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.hero-subtitle {
  font-size: 16px;
  color: #94a3b8;
  line-height: 1.6;
  margin: 0;
}

/* Modes Grid */
.modes-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
}

@media (max-width: 960px) {
  .modes-grid {
    grid-template-columns: 1fr;
  }
}

.mode-card {
  position: relative;
  background: rgba(17, 24, 39, 0.7);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  padding: 28px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 24px;
  backdrop-filter: blur(12px);
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1), border-color 0.2s ease, box-shadow 0.2s ease;
  overflow: hidden;
}

.mode-card:hover {
  transform: translateY(-4px);
  border-color: rgba(255, 255, 255, 0.2);
  box-shadow: 0 20px 40px -15px rgba(0, 0, 0, 0.5);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-icon-box {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.box-resume {
  background: rgba(99, 102, 241, 0.15);
  color: #818cf8;
  border: 1px solid rgba(99, 102, 241, 0.3);
}

.box-tender {
  background: rgba(16, 185, 129, 0.15);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.3);
}

.box-edit {
  background: rgba(14, 165, 233, 0.15);
  color: #38bdf8;
  border: 1px solid rgba(14, 165, 233, 0.3);
}

.badge-feature {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  padding: 3px 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.05);
  color: #94a3b8;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.card-title {
  font-size: 22px;
  font-weight: 700;
  color: #ffffff;
  margin: 0;
  letter-spacing: -0.01em;
}

.card-description {
  font-size: 14px;
  color: #94a3b8;
  line-height: 1.5;
  margin: 0;
}

.card-specs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.spec-tag {
  font-size: 11px;
  padding: 3px 8px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 4px;
  color: #cbd5e1;
}

.card-footer {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.btn-mode-primary {
  width: 100%;
  height: 44px;
  border: none;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #ffffff;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-resume-main {
  background: linear-gradient(135deg, #4f46e5, #6366f1);
  box-shadow: 0 4px 14px rgba(79, 70, 229, 0.35);
}

.btn-resume-main:hover {
  background: linear-gradient(135deg, #4338ca, #4f46e5);
}

.btn-tender-main {
  background: linear-gradient(135deg, #059669, #10b981);
  box-shadow: 0 4px 14px rgba(16, 185, 129, 0.35);
}

.btn-tender-main:hover {
  background: linear-gradient(135deg, #047857, #059669);
}

.btn-edit-main {
  background: linear-gradient(135deg, #0284c7, #0ea5e9);
  box-shadow: 0 4px 14px rgba(14, 165, 233, 0.35);
}

.btn-edit-main:hover {
  background: linear-gradient(135deg, #0369a1, #0284c7);
}

.arrow-icon {
  width: 16px;
  height: 16px;
  transition: transform 0.15s ease;
}

.btn-mode-primary:hover .arrow-icon {
  transform: translateX(3px);
}

.btn-mode-sample {
  width: 100%;
  height: 34px;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #cbd5e1;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-mode-sample:hover {
  background: rgba(255, 255, 255, 0.05);
  border-color: rgba(255, 255, 255, 0.2);
  color: #ffffff;
}

/* Secondary API Card */
.api-card {
  background: rgba(17, 24, 39, 0.6);
  border: 1px solid rgba(245, 158, 11, 0.2);
  border-radius: 16px;
  padding: 32px;
  display: flex;
  flex-direction: column;
  gap: 24px;
  backdrop-filter: blur(12px);
}

.api-card-header {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.api-title-group {
  display: flex;
  align-items: center;
  gap: 12px;
}

.api-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 9999px;
  background: rgba(245, 158, 11, 0.15);
  color: #fbbf24;
  border: 1px solid rgba(245, 158, 11, 0.3);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.api-heading {
  font-size: 20px;
  font-weight: 700;
  color: #ffffff;
  margin: 0;
}

.api-lead {
  font-size: 14px;
  color: #94a3b8;
  line-height: 1.5;
  margin: 0;
}

.code-inline {
  background: rgba(255, 255, 255, 0.08);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: monospace;
  color: #f1f5f9;
  font-size: 13px;
}

.api-endpoints-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

@media (max-width: 768px) {
  .api-endpoints-grid {
    grid-template-columns: 1fr;
  }
}

.endpoint-item {
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 8px;
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.endpoint-badge {
  width: fit-content;
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 3px;
}

.badge-post {
  background: #0284c7;
  color: #ffffff;
}

.badge-get {
  background: #059669;
  color: #ffffff;
}

.endpoint-path {
  font-family: monospace;
  font-size: 13px;
  font-weight: 600;
  color: #f8fafc;
}

.endpoint-desc {
  font-size: 12px;
  color: #94a3b8;
}

.code-snippet-container {
  background: #030712;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  overflow: hidden;
}

.snippet-header {
  height: 44px;
  background: #0b0f19;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
}

.snippet-tabs {
  display: flex;
  gap: 8px;
}

.snippet-tab {
  background: transparent;
  border: none;
  font-size: 12px;
  font-weight: 600;
  color: #94a3b8;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.snippet-tab.is-active {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
}

.btn-copy-code {
  display: flex;
  align-items: center;
  gap: 6px;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #cbd5e1;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-copy-code:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #ffffff;
}

.snippet-body {
  margin: 0;
  padding: 16px;
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.5;
  color: #38bdf8;
  overflow-x: auto;
}

/* Mode Workspace View */
.mode-workspace-view {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.mode-workspace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.btn-back-overview {
  display: flex;
  align-items: center;
  gap: 8px;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #cbd5e1;
  font-size: 13px;
  font-weight: 500;
  padding: 6px 14px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-back-overview:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #ffffff;
}

.mode-tag-pill {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.05em;
  padding: 4px 12px;
  border-radius: 9999px;
}

.pill-resume {
  background: rgba(99, 102, 241, 0.2);
  color: #a5b4fc;
  border: 1px solid rgba(99, 102, 241, 0.4);
}

.pill-tender {
  background: rgba(16, 185, 129, 0.2);
  color: #6ee7b7;
  border: 1px solid rgba(16, 185, 129, 0.4);
}

.pill-edit {
  background: rgba(14, 165, 233, 0.2);
  color: #7dd3fc;
  border: 1px solid rgba(14, 165, 233, 0.4);
}

.mode-workspace-card {
  background: rgba(17, 24, 39, 0.7);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 20px;
  padding: 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 20px;
  backdrop-filter: blur(12px);
}

.mode-icon-circle {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.mode-icon-circle svg {
  width: 32px;
  height: 32px;
}

.circle-resume {
  background: rgba(99, 102, 241, 0.2);
  color: #818cf8;
  border: 1px solid rgba(99, 102, 241, 0.4);
}

.circle-tender {
  background: rgba(16, 185, 129, 0.2);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.4);
}

.circle-edit {
  background: rgba(14, 165, 233, 0.2);
  color: #38bdf8;
  border: 1px solid rgba(14, 165, 233, 0.4);
}

.mode-title {
  font-size: 32px;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin: 0;
}

.mode-description {
  font-size: 15px;
  color: #94a3b8;
  max-width: 600px;
  line-height: 1.5;
  margin: 0;
}

.mode-dropzone {
  width: 100%;
  max-width: 640px;
  padding: 40px 24px;
  margin-top: 12px;
  background: rgba(3, 7, 18, 0.4);
  border: 2px dashed rgba(255, 255, 255, 0.15);
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  transition: all 0.2s ease;
}

.mode-dropzone.is-dragging {
  border-color: #6366f1;
  background: rgba(99, 102, 241, 0.08);
}

.dropzone-cloud-icon {
  width: 44px;
  height: 44px;
  color: #64748b;
}

.dropzone-text {
  font-size: 16px;
  font-weight: 600;
  color: #ffffff;
  margin: 0;
}

.dropzone-sub {
  font-size: 13px;
  color: #64748b;
  margin: 0;
}

.dropzone-cta-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 8px;
}

.btn-primary-upload {
  padding: 10px 20px;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #ffffff;
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-primary-upload.btn-resume {
  background: linear-gradient(135deg, #4f46e5, #6366f1);
}

.btn-primary-upload.btn-tender {
  background: linear-gradient(135deg, #059669, #10b981);
}

.btn-primary-upload.btn-edit {
  background: linear-gradient(135deg, #0284c7, #0ea5e9);
}

.or-divider {
  font-size: 12px;
  color: #64748b;
}

.btn-sample-action {
  padding: 9px 16px;
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  color: #cbd5e1;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-sample-action:hover {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.3);
  color: #ffffff;
}

.mode-highlights {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
  margin-top: 12px;
  background: rgba(0, 0, 0, 0.2);
  padding: 16px 24px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.05);
  width: 100%;
  max-width: 640px;
}

.highlight-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: #cbd5e1;
}

.check-icon {
  width: 16px;
  height: 16px;
  color: #34d399;
  flex-shrink: 0;
}

/* Utilities */
.icon-xs {
  width: 14px;
  height: 14px;
}

.icon-sm {
  width: 16px;
  height: 16px;
}

.text-green {
  color: #34d399;
}

/* Redaction Category Dialog Styles */
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.75);
  backdrop-filter: blur(8px);
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.redact-dialog-card {
  background: #0f172a;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 16px;
  width: 100%;
  max-width: 480px;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.6);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-header {
  padding: 20px 24px 16px;
  display: flex;
  align-items: flex-start;
  gap: 16px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  position: relative;
}

.modal-header-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: rgba(99, 102, 241, 0.15);
  border: 1px solid rgba(99, 102, 241, 0.3);
  color: #818cf8;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.modal-title {
  font-size: 18px;
  font-weight: 700;
  color: #ffffff;
  margin: 0 0 4px;
}

.modal-subtitle {
  font-size: 13px;
  color: #94a3b8;
  margin: 0;
  line-height: 1.4;
}

.btn-close-modal {
  position: absolute;
  top: 16px;
  right: 16px;
  background: transparent;
  border: none;
  color: #94a3b8;
  font-size: 24px;
  cursor: pointer;
  line-height: 1;
}

.btn-close-modal:hover {
  color: #ffffff;
}

.modal-body {
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.categories-loading {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #94a3b8;
  font-size: 14px;
  padding: 20px 0;
  justify-content: center;
}

.category-toggle-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.category-toggle-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.category-toggle-item:hover {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.15);
}

.category-toggle-item.is-active {
  background: rgba(99, 102, 241, 0.08);
  border-color: rgba(99, 102, 241, 0.3);
}

.category-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.category-label {
  font-size: 14px;
  font-weight: 600;
  color: #f1f5f9;
}

.category-id {
  font-size: 11px;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.toggle-switch {
  position: relative;
  display: inline-block;
  width: 44px;
  height: 24px;
  flex-shrink: 0;
}

.toggle-switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-slider {
  position: absolute;
  cursor: pointer;
  inset: 0;
  background-color: #334155;
  transition: 0.25s ease;
  border-radius: 24px;
}

.toggle-slider:before {
  position: absolute;
  content: "";
  height: 18px;
  width: 18px;
  left: 3px;
  bottom: 3px;
  background-color: #ffffff;
  transition: 0.25s ease;
  border-radius: 50%;
}

.toggle-switch input:checked + .toggle-slider {
  background-color: #6366f1;
}

.toggle-switch input:checked + .toggle-slider:before {
  transform: translateX(20px);
}

.modal-footer {
  padding: 16px 24px 20px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.btn-confirm-redact {
  background: linear-gradient(135deg, #6366f1 0%, #4f46e5 100%);
  color: #ffffff;
  padding: 10px 20px;
  font-weight: 600;
  border-radius: 10px;
  border: none;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-confirm-redact:hover {
  background: linear-gradient(135deg, #4f46e5 0%, #4338ca 100%);
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.4);
}
</style>
