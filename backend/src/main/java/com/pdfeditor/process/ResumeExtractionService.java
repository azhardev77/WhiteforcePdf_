package com.pdfeditor.process;

import com.pdfeditor.extraction.ExtractionPipeline;
import com.pdfeditor.model.*;
import com.pdfeditor.process.dto.ResumeExtractResponse;
import com.pdfeditor.process.exception.ResumeParsingException;
import com.pdfeditor.rules.PiiDetector;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ResumeExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractionService.class);

    private final ExtractionPipeline extractionPipeline;

    // Blacklist words for candidate name heuristic
    private static final Set<String> BLACKLISTED_NAME_TERMS = Set.of(
            "resume", "curriculum vitae", "cv", "profile", "summary",
            "contact", "contact info", "contact information", "experience",
            "work experience", "education", "skills", "projects", "certifications",
            "software engineer", "developer", "engineer", "architect", "lead",
            "manager", "director", "designer", "consultant", "analyst", "intern",
            "full stack", "frontend", "backend", "data scientist", "student"
    );

    // Standard section mappings
    private static final Map<String, List<String>> SECTION_KEYWORDS = new LinkedHashMap<>();
    static {
        SECTION_KEYWORDS.put("SUMMARY", List.of("summary", "professional summary", "career summary", "executive summary", "profile", "personal profile", "about me", "objective", "career objective"));
        SECTION_KEYWORDS.put("EXPERIENCE", List.of("experience", "professional experience", "employment history", "employment", "career history", "relevant experience"));
        SECTION_KEYWORDS.put("WORK HISTORY", List.of("work history", "history of employment"));
        SECTION_KEYWORDS.put("EDUCATION", List.of("education", "educational background", "academic background", "academics", "qualifications", "academic qualifications"));
        SECTION_KEYWORDS.put("SKILLS", List.of("skills", "technical skills", "core competencies", "skills & competencies", "technologies", "key skills", "areas of expertise", "expertise", "programming languages"));
        SECTION_KEYWORDS.put("PROJECTS", List.of("projects", "personal projects", "academic projects", "key projects", "notable projects"));
        SECTION_KEYWORDS.put("CERTIFICATIONS", List.of("certifications", "certificates", "licenses & certifications", "certifications & licenses", "accreditations", "awards & certifications"));
    }

    // Predefined skill taxonomy
    private static final List<String> SKILL_TAXONOMY = List.of(
            // Languages with symbols & special characters
            "C++", "C#", ".NET", "ASP.NET", "Node.js", "Vue.js", "React.js", "Next.js", "AngularJS",
            "Java", "Python", "JavaScript", "TypeScript", "Golang", "Go", "Rust", "Ruby", "PHP",
            "Swift", "Kotlin", "Scala", "R", "SQL", "PL/SQL", "HTML", "HTML5", "CSS", "CSS3", "Sass", "SCSS",

            // Frameworks & Libraries
            "Spring Boot", "Spring MVC", "Spring", "Hibernate", "React", "Vue", "Angular", "Express.js", "Express",
            "Django", "Flask", "FastAPI", "Ruby on Rails", "Laravel", "Tailwind CSS", "Bootstrap",

            // Databases & Cache
            "PostgreSQL", "MySQL", "MongoDB", "Redis", "Elasticsearch", "Oracle", "Cassandra",
            "SQLite", "DynamoDB", "MariaDB", "Neo4j",

            // Cloud, DevOps & Tools
            "Docker", "Kubernetes", "AWS", "Amazon Web Services", "Azure", "GCP", "Google Cloud",
            "Git", "GitHub", "GitLab", "CI/CD", "Jenkins", "Terraform", "Ansible", "Linux",
            "Kafka", "RabbitMQ", "GraphQL", "REST API", "Microservices",

            // AI / ML / Data
            "TensorFlow", "PyTorch", "Scikit-Learn", "Pandas", "NumPy", "Apache Spark", "Hadoop"
    );

    private static final Map<String, Pattern> SKILL_PATTERNS = new LinkedHashMap<>();
    static {
        for (String skill : SKILL_TAXONOMY) {
            boolean caseSensitive = "Go".equals(skill) || "R".equals(skill);
            String regex = "(?<![a-zA-Z0-9+#.-])" + Pattern.quote(skill) + "(?![a-zA-Z0-9+#.-])";
            Pattern pattern = Pattern.compile(regex, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
            SKILL_PATTERNS.put(skill, pattern);
        }
    }

    private record NameCandidate(String name, double fontSize, boolean isProminent, double y) {}

    public ResumeExtractionService(ExtractionPipeline extractionPipeline) {
        this.extractionPipeline = extractionPipeline;
    }

    /**
     * Executes single-pass resume parsing and data extraction.
     */
    public ResumeExtractResponse extract(byte[] pdfBytes, String filename) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ResumeParsingException("PDF bytes cannot be empty", 400);
        }
        String safeFilename = (filename != null && !filename.isBlank()) ? filename : "resume.pdf";

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.isEncrypted()) {
                throw new ResumeParsingException("Password-protected or encrypted PDFs are not supported", 422);
            }

            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new ResumeParsingException("PDF document has no pages", 422);
            }

            // 1. Single pass: Extract clickable links during PDDocument traversal
            List<String> annotationLinks = extractClickableLinks(document);

            // 2. Single pass: Reuse loaded PDDocument to build spatial DocumentModel
            DocumentModel docModel = extractionPipeline.extractDocument(document, UUID.randomUUID().toString(), safeFilename);

            // 3. Name heuristic: Search restricted to Page 1, top 25% height, prominent font, blacklist filtered
            String candidateName = null;
            if (!docModel.getPages().isEmpty()) {
                candidateName = extractCandidateName(docModel.getPages().get(0));
            }

            // 4. Section segmentation engine
            Map<String, List<String>> sections = segmentSections(docModel);

            // 5. Extract full text from spatial layout for NLP & contact parsing
            String fullText = extractFullText(docModel);

            // 6. Contact extraction reusing PiiDetector & link matching
            ResumeExtractResponse.ContactInfo contact = extractContactInfo(fullText, annotationLinks);

            // 7. Symbol-safe technical skill detection
            List<String> skills = extractSkills(fullText, sections);

            // 8. Accurate metadata calculation
            int wordCount = calculateWordCount(docModel);
            ResumeExtractResponse.Metadata metadata = new ResumeExtractResponse.Metadata(pageCount, wordCount);

            return new ResumeExtractResponse(
                    candidateName,
                    contact,
                    skills,
                    sections,
                    metadata
            );
        } catch (InvalidPasswordException e) {
            log.warn("Resume parsing rejected: encrypted/password-protected PDF");
            throw new ResumeParsingException("Password-protected or encrypted PDFs are not supported", e, 422);
        } catch (ResumeParsingException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to parse resume PDF: {}", e.getMessage(), e);
            throw new ResumeParsingException("Failed to parse resume PDF: " + e.getMessage(), e, 422);
        }
    }

    private List<String> extractClickableLinks(PDDocument document) {
        Set<String> links = new LinkedHashSet<>();
        for (PDPage page : document.getPages()) {
            try {
                List<PDAnnotation> annotations = page.getAnnotations();
                if (annotations == null) continue;

                for (PDAnnotation annot : annotations) {
                    if (annot instanceof PDAnnotationLink link) {
                        String uri = null;
                        if (link.getAction() instanceof PDActionURI actionUri) {
                            uri = actionUri.getURI();
                        }
                        if (uri == null) {
                            COSDictionary cosDict = link.getCOSObject();
                            COSBase aBase = cosDict.getDictionaryObject(COSName.A);
                            if (aBase instanceof COSDictionary aDict) {
                                uri = aDict.getString(COSName.URI);
                            }
                        }
                        if (uri != null && !uri.isBlank()) {
                            uri = uri.trim();
                            if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("www.")) {
                                links.add(uri);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read annotations on page: {}", e.getMessage());
            }
        }
        return new ArrayList<>(links);
    }

    private String extractCandidateName(PageModel page) {
        if (page == null || page.getBlocks() == null || page.getBlocks().isEmpty()) {
            return null;
        }

        double pageHeight = page.getHeight() > 0 ? page.getHeight() : 792.0;
        double topThreshold = pageHeight * 0.25;

        NameCandidate bestCandidate = null;

        for (Block block : page.getBlocks()) {
            if (block == null) continue;
            double[] bbox = block.getBbox();
            double y = (bbox != null && bbox.length > 1) ? bbox[1] : 0.0;

            if (y > topThreshold) {
                continue; // Restrict search to top 25% of page 1
            }

            String blockText = getBlockText(block);
            if (blockText == null || blockText.isBlank()) {
                continue;
            }

            double maxFontSize = getMaxFontSize(block);
            boolean isProminent = (block instanceof HeadingBlock) || isBlockBold(block);

            String[] lines = blockText.split("\\r?\\n");
            for (String line : lines) {
                String candidate = line.trim();
                if (isValidNameCandidate(candidate)) {
                    NameCandidate current = new NameCandidate(candidate, maxFontSize, isProminent, y);
                    if (bestCandidate == null || isBetterCandidate(current, bestCandidate)) {
                        bestCandidate = current;
                    }
                }
            }
        }

        return bestCandidate != null ? bestCandidate.name() : null;
    }

    private boolean isValidNameCandidate(String text) {
        if (text == null || text.isBlank()) return false;

        String[] words = text.split("\\s+");
        if (words.length < 1 || words.length > 4) {
            return false;
        }

        if (text.matches(".*\\d.*")) {
            return false; // Names do not contain numbers
        }

        if (text.contains("@") || text.contains("www.") || text.contains("http") ||
                text.contains("github") || text.contains("linkedin")) {
            return false; // Exclude email/URL strings
        }

        if (text.matches(".*[|•:;\\\\/_=<>+*?%^$#@!~].*")) {
            return false; // Exclude symbols and delimiters
        }

        if (!text.matches("^[\\p{L}\\s.'-]+$")) {
            return false; // Must contain valid Unicode letters, spaces, hyphens, periods
        }

        String lower = text.toLowerCase();
        for (String blacklisted : BLACKLISTED_NAME_TERMS) {
            if (lower.contains(blacklisted)) {
                return false;
            }
        }

        return true;
    }

    private boolean isBetterCandidate(NameCandidate current, NameCandidate best) {
        if (Math.abs(current.fontSize() - best.fontSize()) > 0.5) {
            return current.fontSize() > best.fontSize();
        }
        if (current.isProminent() != best.isProminent()) {
            return current.isProminent();
        }
        return current.y() < best.y();
    }

    private Map<String, List<String>> segmentSections(DocumentModel docModel) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String currentSection = null;

        if (docModel == null || docModel.getPages() == null) {
            return sections;
        }

        for (PageModel page : docModel.getPages()) {
            List<Block> blocks = page.getBlocks();
            if (blocks == null) continue;

            for (Block block : blocks) {
                List<String> blockTexts = getAtomicTexts(block);

                for (String textChunk : blockTexts) {
                    if (textChunk == null || textChunk.isBlank()) continue;

                    String[] lines = textChunk.split("\\r?\\n");
                    for (String line : lines) {
                        String trimmedLine = line.trim();
                        if (trimmedLine.isBlank()) continue;

                        String heading = matchHeadingText(trimmedLine);
                        if (heading != null) {
                            currentSection = heading;
                            sections.computeIfAbsent(currentSection, k -> new ArrayList<>());
                        } else if (currentSection != null) {
                            sections.get(currentSection).add(trimmedLine);
                        }
                    }
                }
            }
        }

        return sections;
    }

    private List<String> getAtomicTexts(Block block) {
        if (block == null) return Collections.emptyList();
        if (block instanceof TableBlock tb) {
            List<String> texts = new ArrayList<>();
            if (tb.getCells() != null) {
                for (List<ParagraphBlock> row : tb.getCells()) {
                    if (row != null) {
                        for (ParagraphBlock cell : row) {
                            if (cell != null && cell.getText() != null && !cell.getText().isBlank()) {
                                texts.add(cell.getText());
                            }
                        }
                    }
                }
            }
            return texts;
        } else if (block instanceof ListBlock lb) {
            List<String> texts = new ArrayList<>();
            if (lb.getItems() != null) {
                for (ParagraphBlock pb : lb.getItems()) {
                    if (pb != null && pb.getText() != null && !pb.getText().isBlank()) {
                        texts.add(pb.getText());
                    }
                }
            }
            return texts;
        } else {
            String text = getBlockText(block);
            return (text != null && !text.isBlank()) ? List.of(text) : Collections.emptyList();
        }
    }

    private String matchHeadingText(String rawText) {
        if (rawText == null || rawText.isBlank()) return null;

        String cleanText = rawText.replaceAll("^[0-9]+[.\\s]+", "");
        cleanText = cleanText.replaceAll("[:\\-_|•]+$", "");
        cleanText = cleanText.trim().toLowerCase();

        String[] words = cleanText.split("\\s+");
        if (words.length > 5) {
            return null;
        }

        for (Map.Entry<String, List<String>> entry : SECTION_KEYWORDS.entrySet()) {
            String canonical = entry.getKey();
            if (cleanText.equalsIgnoreCase(canonical)) {
                return canonical;
            }
            for (String kw : entry.getValue()) {
                if (cleanText.equals(kw)) {
                    return canonical;
                }
            }
        }

        return null;
    }

    private List<String> extractSkills(String fullText, Map<String, List<String>> sections) {
        Set<String> detected = new LinkedHashSet<>();

        StringBuilder skillsSectionBuilder = new StringBuilder();
        if (sections != null && sections.containsKey("SKILLS")) {
            for (String blockStr : sections.get("SKILLS")) {
                skillsSectionBuilder.append(blockStr).append("\n");
            }
        }
        String skillsSectionText = skillsSectionBuilder.toString();

        for (Map.Entry<String, Pattern> entry : SKILL_PATTERNS.entrySet()) {
            String skill = entry.getKey();
            Pattern pattern = entry.getValue();

            if (!skillsSectionText.isBlank() && pattern.matcher(skillsSectionText).find()) {
                detected.add(skill);
            } else if (fullText != null && pattern.matcher(fullText).find()) {
                detected.add(skill);
            }
        }

        return new ArrayList<>(detected);
    }

    private ResumeExtractResponse.ContactInfo extractContactInfo(String fullText, List<String> annotationLinks) {
        Set<String> emails = new LinkedHashSet<>();
        Set<String> phones = new LinkedHashSet<>();
        Set<String> links = new LinkedHashSet<>(annotationLinks);

        if (fullText != null && !fullText.isBlank()) {
            List<PiiDetector.PiiSpan> spans = PiiDetector.findPIISpans(fullText);
            for (PiiDetector.PiiSpan span : spans) {
                if ("email".equalsIgnoreCase(span.getType()) && span.getMatch() != null) {
                    String cleaned = cleanEmail(span.getMatch());
                    if (cleaned != null && !cleaned.isBlank()) {
                        emails.add(cleaned);
                    }
                } else if ("phone".equalsIgnoreCase(span.getType()) && span.getMatch() != null) {
                    String cleaned = cleanPhone(span.getMatch());
                    if (cleaned != null && !cleaned.isBlank()) {
                        phones.add(cleaned);
                    }
                }
            }

            Pattern urlPattern = Pattern.compile("(?i)\\b((?:https?://|www\\.)\\S+|(?:linkedin\\.com/in|github\\.com)/\\S+)");
            Matcher urlMatcher = urlPattern.matcher(fullText);
            while (urlMatcher.find()) {
                String url = urlMatcher.group(1);
                if (url != null) {
                    url = url.replaceAll("[.,;:)]+$", "").trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    links.add(url);
                }
            }
        }

        return new ResumeExtractResponse.ContactInfo(
                new ArrayList<>(emails),
                new ArrayList<>(phones),
                new ArrayList<>(links)
        );
    }

    private String cleanEmail(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("^[\\u2600-\\u27BF\\uE000-\\uF8FF\\x{1F300}-\\x{1F9FF}\\u260E\\u2709\\uF095\\uF0E0✉☎✆📞📧📱📍\\s]+", "");
        cleaned = cleaned.replaceAll("(?i)^(?:email|e-mail)\\s*[:.-]?\\s*", "");
        cleaned = cleaned.replaceAll("[.,;:)]+$", "").trim();
        return cleaned.toLowerCase();
    }

    private String cleanPhone(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("^[\\u2600-\\u27BF\\uE000-\\uF8FF\\x{1F300}-\\x{1F9FF}\\u260E\\u2709\\uF095\\uF0E0✉☎✆📞📧📱📍\\s]+", "");
        cleaned = cleaned.replaceAll("(?i)^(?:phone|tel|telephone|mobile|mob|cell|call|contact)\\s*[:.-]?\\s*", "");
        cleaned = cleaned.replaceAll("[.,;:)]+$", "").trim();
        return cleaned;
    }

    private String extractFullText(DocumentModel docModel) {
        if (docModel == null || docModel.getPages() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (PageModel page : docModel.getPages()) {
            if (page.getBlocks() != null) {
                for (Block block : page.getBlocks()) {
                    String text = getBlockText(block);
                    if (text != null && !text.isBlank()) {
                        sb.append(text).append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private int calculateWordCount(DocumentModel docModel) {
        String fullText = extractFullText(docModel);
        if (fullText.isBlank()) {
            return 0;
        }
        String[] words = fullText.trim().split("\\s+");
        return words.length;
    }

    private String getBlockText(Block block) {
        if (block == null) return "";
        if (block instanceof HeadingBlock hb) {
            return hb.getText();
        } else if (block instanceof ParagraphBlock pb) {
            return pb.getText();
        } else if (block instanceof ListBlock lb) {
            StringBuilder sb = new StringBuilder();
            if (lb.getItems() != null) {
                for (ParagraphBlock pb : lb.getItems()) {
                    if (pb != null && pb.getText() != null) {
                        sb.append(pb.getText()).append(" ");
                    }
                }
            }
            return sb.toString().trim();
        } else if (block instanceof TableBlock tb) {
            StringBuilder sb = new StringBuilder();
            if (tb.getCells() != null) {
                for (List<ParagraphBlock> row : tb.getCells()) {
                    if (row != null) {
                        for (ParagraphBlock cell : row) {
                            if (cell != null && cell.getText() != null) {
                                sb.append(cell.getText()).append(" ");
                            }
                        }
                    }
                }
            }
            return sb.toString().trim();
        }
        return "";
    }

    private List<TextRun> getBlockRuns(Block block) {
        if (block instanceof HeadingBlock hb) {
            return hb.getRuns();
        } else if (block instanceof ParagraphBlock pb) {
            return pb.getRuns();
        } else if (block instanceof ListBlock lb) {
            List<TextRun> all = new ArrayList<>();
            if (lb.getItems() != null) {
                for (ParagraphBlock pb : lb.getItems()) {
                    if (pb != null && pb.getRuns() != null) {
                        all.addAll(pb.getRuns());
                    }
                }
            }
            return all;
        }
        return Collections.emptyList();
    }

    private double getMaxFontSize(Block block) {
        double max = 0.0;
        for (TextRun run : getBlockRuns(block)) {
            if (run != null && run.getStyle() != null) {
                max = Math.max(max, run.getStyle().fontSize());
            }
        }
        return max;
    }

    private boolean isBlockBold(Block block) {
        for (TextRun run : getBlockRuns(block)) {
            if (run != null && run.getStyle() != null && run.getStyle().bold()) {
                return true;
            }
        }
        return false;
    }
}
