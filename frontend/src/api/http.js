import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  timeout: 60000,
  headers: {
    'Accept': 'application/json'
  }
});

// Request interceptor to automatically attach X-API-KEY header
api.interceptors.request.use((config) => {
  const apiKey = import.meta.env.VITE_API_KEY;
  if (apiKey) {
    config.headers['X-API-KEY'] = apiKey;
  }
  return config;
});

// Response interceptor for unified, user-friendly error formatting
api.interceptors.response.use(
  (response) => response,
  (error) => {
    let message = 'An unexpected error occurred';
    if (error.response) {
      const data = error.response.data;
      if (typeof data === 'string' && data.trim()) {
        message = data;
      } else if (data && data.message) {
        message = data.message;
      } else if (data && data.error) {
        message = data.error;
      } else {
        message = `Server responded with status ${error.response.status}: ${error.response.statusText}`;
      }
    } else if (error.request) {
      message = 'Backend server is unreachable. Please verify that the Spring Boot server is running on http://localhost:8080.';
    } else if (error.message) {
      message = error.message;
    }
    const err = new Error(message);
    err.response = error.response;
    err.status = error.response?.status;
    return Promise.reject(err);
  }
);

export default api;
