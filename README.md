# PDF Visual Studio & In-Place Redaction Editor

A pixel-perfect PDF document editor and visual redaction studio built with Spring Boot, Apache PDFBox, Vue 3, Pinia, and PDF.js.

## Features
- **1:1 PDF.js Canvas Rendering**: Pixel-perfect layout fidelity matching the original PDF.
- **Interactive Line-Level Overlays**: Automatic clustering and blank partitioning for text and form fields.
- **In-Place Redactions & Whiteout**: Erases underlying original text completely while preventing ghosting.
- **Form-Fill Overprinting**: Intelligently preserves underlying dotted lines and form lines when typing directly onto blanks.
- **Dynamic Font-Scaling**: Auto-scales text to prevent horizontal collisions with adjacent words.
- **Lossless In-Place Export**: Apache PDFBox applies whiteout and draws replacement text with coordinate transformations.

## Tech Stack
- **Frontend**: Vue 3, Vite, Pinia, PDF.js
- **Backend**: Java 21, Spring Boot 3.3.4, Apache PDFBox 3.0.3

## Running Locally

### Single-Command Start (Root)
Run both backend and frontend concurrently with one command from the project root:
```bash
npm install     # First time only to install concurrently
npm run dev     # Starts both Spring Boot backend & Vite frontend concurrently
```

### Or Run Individually

#### Backend
```bash
cd backend
mvn spring-boot:run
```
API runs on `http://localhost:8080`.

#### Frontend
```bash
cd frontend
npm install
npm run dev
```
Dev server runs on `http://localhost:5173`.
