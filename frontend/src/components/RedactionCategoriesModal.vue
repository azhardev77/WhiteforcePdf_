<template>
  <transition name="fade">
    <div
      class="modal-backdrop"
      data-testid="redact-categories-modal"
      @click.self="$emit('cancel')"
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
          <button type="button" class="btn-close-modal" @click="$emit('cancel')">&times;</button>
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
          <button type="button" class="btn-secondary" @click="$emit('cancel')">Cancel</button>
          <button
            type="button"
            class="btn-primary btn-confirm-redact"
            data-testid="btn-confirm-redact"
            :disabled="isLoadingCategories"
            @click="onConfirm"
          >
            Continue to Editor
          </button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { fetchRedactionCategories } from '@/api/sessions';

const emit = defineEmits(['confirm', 'cancel']);

const STORAGE_KEY_REDACT_TOGGLES = 'pdf_redaction_toggles';

const isLoadingCategories = ref(false);
const availableCategories = ref([
  { id: 'email', label: 'Email Addresses', default: true },
  { id: 'phone', label: 'Phone Numbers', default: true }
]);
const categoryToggles = ref({
  email: true,
  phone: true
});

onMounted(async () => {
  isLoadingCategories.value = true;
  try {
    const fetched = await fetchRedactionCategories();
    if (Array.isArray(fetched) && fetched.length > 0) {
      availableCategories.value = fetched;
    }
  } catch (err) {
    console.warn('[RedactionModal] Could not load redaction categories, using defaults:', err);
  } finally {
    isLoadingCategories.value = false;
  }

  let saved = null;
  try {
    const raw = localStorage.getItem(STORAGE_KEY_REDACT_TOGGLES);
    if (raw) saved = JSON.parse(raw);
  } catch (e) {
    console.warn('[RedactionModal] Failed to parse saved toggle state:', e);
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
});

function onConfirm() {
  try {
    localStorage.setItem(STORAGE_KEY_REDACT_TOGGLES, JSON.stringify(categoryToggles.value));
  } catch (e) {
    console.warn('[RedactionModal] Failed to save toggle state to localStorage:', e);
  }

  const selectedCategories = Object.keys(categoryToggles.value).filter(id => Boolean(categoryToggles.value[id]));
  emit('confirm', selectedCategories);
}
</script>

<style scoped>
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
  color: #f8fafc;
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

.btn-secondary {
  background: rgba(255, 255, 255, 0.08);
  color: #e2e8f0;
  border: 1px solid rgba(255, 255, 255, 0.12);
  padding: 10px 18px;
  border-radius: 10px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-secondary:hover {
  background: rgba(255, 255, 255, 0.14);
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

.btn-confirm-redact:hover:not(:disabled) {
  background: linear-gradient(135deg, #4f46e5 0%, #4338ca 100%);
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.4);
}

.btn-confirm-redact:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>

