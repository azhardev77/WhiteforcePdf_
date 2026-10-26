import { createRouter, createWebHistory } from 'vue-router';
import HomeView from '@/views/HomeView.vue';
import EditorView from '@/views/EditorView.vue';

const routes = [
  {
    path: '/',
    name: 'home',
    component: HomeView
  },
  {
    path: '/resume',
    name: 'resume',
    component: HomeView,
    props: { initialMode: 'RESUME' }
  },
  {
    path: '/tender',
    name: 'tender',
    component: HomeView,
    props: { initialMode: 'TENDER' }
  },
  {
    path: '/edit',
    name: 'edit',
    component: HomeView,
    props: { initialMode: 'EDIT' }
  },
  {
    path: '/edit/:sessionId',
    name: 'edit-session',
    component: EditorView,
    props: true
  },
  {
    path: '/editor',
    name: 'editor',
    component: EditorView
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

export default router;
