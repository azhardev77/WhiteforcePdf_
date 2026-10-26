const fs = require('fs');
const path = require('path');

async function main() {
    console.log('=== Running Phase 3b pdfjs getTextContent Verification ===');

    // Dynamic import for ES module pdfjs-dist
    const pdfjsDist = await import('./frontend/node_modules/pdfjs-dist/legacy/build/pdf.mjs');

    const expectedFile = path.resolve(__dirname, 'backend/src/test/resources/fixtures/EXPECTED.txt');
    if (!fs.existsSync(expectedFile)) {
        console.error('EXPECTED.txt not found at ' + expectedFile);
        process.exit(1);
    }

    const lines = fs.readFileSync(expectedFile, 'utf8').split('\n');
    const fixtures = [];

    for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith('#')) continue;
        const parts = trimmed.split('|').map(s => s.trim());
        if (parts.length >= 3) {
            fixtures.push({
                filename: parts[0],
                email: parts[1],
                phone: parts[2],
                nonPii: ['Test User', 'Jabalpur', 'Software Developer']
            });
        }
    }

    // Add resume.pdf
    fixtures.push({
        filename: 'resume.pdf',
        email: 'azharmansoori725@gmail.com',
        phone: '+91-7747047876',
        nonPii: ['Mohd Azhar Mansoori', 'Jabalpur', 'Software Developer']
    });

    const outputDir = path.resolve(__dirname, 'backend/target/phase3b-output');
    if (!fs.existsSync(outputDir)) {
        console.error('Output directory not found at ' + outputDir + '. Did SessionPhase3bVerificationTest run?');
        process.exit(1);
    }

    let allPass = true;
    const results = [];

    for (const fixture of fixtures) {
        const pdfPath = path.join(outputDir, fixture.filename);
        console.log(`\nChecking fixture: ${fixture.filename}`);
        if (!fs.existsSync(pdfPath)) {
            console.error(`  FAIL: Output file not found: ${pdfPath}`);
            results.push({ fixture: fixture.filename, status: 'FAIL', reason: 'Output file missing' });
            allPass = false;
            continue;
        }

        const data = new Uint8Array(fs.readFileSync(pdfPath));
        const loadingTask = pdfjsDist.getDocument({
            data,
            disableFontFace: true,
            isEvalSupported: false,
            useSystemFonts: true
        });

        const doc = await loadingTask.promise;
        console.log(`  Loaded PDF, total pages: ${doc.numPages}`);

        let fullText = '';
        const allItems = [];

        for (let p = 1; p <= doc.numPages; p++) {
            const page = await doc.getPage(p);
            const textContent = await page.getTextContent();
            for (const item of textContent.items) {
                if (item.str && item.str.trim()) {
                    allItems.push(item.str);
                }
            }
            const pageText = textContent.items.map(item => item.str).join(' ');
            fullText += ' ' + pageText;
        }

        const digitsPhone = fixture.phone.replace(/\D/g, '');
        const cleanFullText = fullText.replace(/\s+/g, ' ');
        const digitsOnlyText = fullText.replace(/\D/g, '');

        // Check for email
        const emailFound = fullText.includes(fixture.email) || allItems.some(str => str.includes(fixture.email));
        // Check for phone
        const phoneFound = fullText.includes(fixture.phone) || digitsOnlyText.includes(digitsPhone);

        // Check for non-PII
        const missingNonPii = fixture.nonPii.filter(np => !fullText.includes(np));

        let fixturePass = true;
        let reasons = [];

        if (emailFound) {
            fixturePass = false;
            reasons.push(`Email '${fixture.email}' found in pdfjs text`);
        }
        if (phoneFound) {
            fixturePass = false;
            reasons.push(`Phone '${fixture.phone}' or digits '${digitsPhone}' found in pdfjs text`);
        }
        if (missingNonPii.length > 0) {
            fixturePass = false;
            reasons.push(`Missing non-PII text: ${missingNonPii.join(', ')}`);
        }

        if (fixturePass) {
            console.log(`  PASS: No email, no phone, non-PII preserved.`);
            results.push({ fixture: fixture.filename, status: 'PASS' });
        } else {
            console.error(`  FAIL: ${reasons.join('; ')}`);
            results.push({ fixture: fixture.filename, status: 'FAIL', reasons });
            allPass = false;
        }
    }

    console.log('\n============================================================');
    console.log('                 PDF.JS VERIFICATION SUMMARY                ');
    console.log('============================================================');
    for (const r of results) {
        console.log(`- ${r.fixture.padEnd(35)} : ${r.status} ${r.reasons ? '(' + r.reasons.join(', ') + ')' : ''}`);
    }
    console.log('============================================================');

    if (!allPass) {
        console.error('Phase 3b pdfjs verification FAILED.');
        process.exit(1);
    } else {
        console.log('Phase 3b pdfjs verification PASSED for all fixtures.');
        process.exit(0);
    }
}

main().catch(err => {
    console.error('Fatal error in pdfjs verification:', err);
    process.exit(1);
});
