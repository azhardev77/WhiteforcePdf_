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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ResumeExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeExtractionService.class);

    private final ExtractionPipeline extractionPipeline;

    // Strict blacklist for candidate name detection heuristic
    private static final Set<String> BLACKLISTED_NAME_TERMS = Set.of(
            "resume", "curriculum vitae", "cv", "profile", "summary",
            "contact", "contact info", "contact information", "experience",
            "work experience", "education", "skills", "projects", "certifications",
            "software engineer", "backend developer", "full stack developer",
            "frontend developer", "developer", "data scientist", "architect",
            "lead", "manager", "director", "designer", "consultant", "analyst", "intern"
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

    // Tier 1: Unambiguous skills (Case-Insensitive matching)
    private static final List<String> UNAMBIGUOUS_SKILLS = List.of(
            "spring boot", "postgresql", "mysql", "mongodb", "docker", "kubernetes",
            "typescript", "vue.js", "node.js", "aws", "gcp", "redis", "elasticsearch", "kafka",
            "python", "golang", "kotlin", "swift", "scala", "php", "ruby", "html", "html5",
            "css", "css3", "sass", "scss", "sql", "pl/sql", ".net", "asp.net", "django",
            "flask", "fastapi", "laravel", "tailwind css", "bootstrap", "hibernate",
            "express.js", "graphql", "rest api", "microservices", "ci/cd", "jenkins",
            "terraform", "ansible", "linux", "azure", "git", "github", "gitlab",
            "tensorflow", "pytorch", "pandas", "numpy", "apache spark", "hadoop"
    );

    // Tier 2: Case-sensitive skills (Collides with common English words / verbs)
    private static final Map<String, String> CASE_SENSITIVE_SKILLS_MAP = Map.of(
            "Spring", "(?<![a-zA-Z0-9+#.-])Spring(?!\\s+(?:Boot|MVC|Batch))(?![a-zA-Z0-9+#.-])",
            "React", "(?<![a-zA-Z0-9+#.-])React(?!\\.js|\\s+Native)(?![a-zA-Z0-9+#.-])",
            "Express", "(?<![a-zA-Z0-9+#.-])Express(?!\\.js)(?![a-zA-Z0-9+#.-])",
            "Java", "(?<![a-zA-Z0-9+#.-])Java(?![a-zA-Z0-9+#.-])",
            "Go", "(?<![a-zA-Z0-9+#.-])Go(?![a-zA-Z0-9+#.-])",
            "Rust", "(?<![a-zA-Z0-9+#.-])Rust(?![a-zA-Z0-9+#.-])",
            "C++", "(?<![a-zA-Z0-9+#.-])\\QC++\\E(?![a-zA-Z0-9+#.-])",
            "C#", "(?<![a-zA-Z0-9+#.-])\\QC#\\E(?![a-zA-Z0-9+#.-])"
    );

    private static final Map<String, Pattern> UNAMBIGUOUS_SKILL_PATTERNS = new LinkedHashMap<>();
    private static final Map<String, Pattern> CASE_SENSITIVE_SKILL_PATTERNS = new LinkedHashMap<>();

    // Single-letter language safety regex
    private static final Pattern C_LANGUAGE_PATTERN = Pattern.compile("(?i)(?:\\bC\\s*/\\s*C\\+\\+|\\bC\\s+(?:Language|Programming)\\b)");
    private static final Pattern R_LANGUAGE_PATTERN = Pattern.compile("(?i)(?:\\bR\\s+(?:Language|Programming)\\b|\\bR\\s*/\\s*Python\\b|\\bPython\\s*/\\s*R\\b)");

    static {
        for (String skill : UNAMBIGUOUS_SKILLS) {
            String regex = "(?<![a-zA-Z0-9+#.-])" + Pattern.quote(skill) + "(?![a-zA-Z0-9+#.-])";
            UNAMBIGUOUS_SKILL_PATTERNS.put(skill, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }

        for (Map.Entry<String, String> entry : CASE_SENSITIVE_SKILLS_MAP.entrySet()) {
            CASE_SENSITIVE_SKILL_PATTERNS.put(entry.getKey(), Pattern.compile(entry.getValue())); // Strict case
        }
    }

    private record NameCandidate(String name, double fontSize, boolean isProminent, double y) {}

    public ResumeExtractionService(ExtractionPipeline extractionPipeline) {
        this.extractionPipeline = extractionPipeline;
    }

    /**
     * Executes single-pass resume parsing and structured data extraction.
     */
    public ResumeExtractResponse extract(byte[] pdfBytes, String filename) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new ResumeParsingException(HttpStatus.BAD_REQUEST, "PDF bytes cannot be empty");
        }
        String safeFilename = (filename != null && !filename.isBlank()) ? filename : "resume.pdf";

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.isEncrypted()) {
                throw new ResumeParsingException(HttpStatus.UNPROCESSABLE_ENTITY, "Password-protected or encrypted PDFs are not supported");
            }

            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new ResumeParsingException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF document has no pages");
            }

            // 1. Single pass: Extract clickable hyperlinks from PDAnnotationLink during document traversal
            List<String> annotationLinks = extractClickableLinks(document);

            // 2. Single pass: Reuse loaded PDDocument to build spatial DocumentModel without re-reading bytes
            DocumentModel docModel = extractionPipeline.extractDocument(document, UUID.randomUUID().toString(), safeFilename);

            // 3. Name heuristic: Search restricted to Page 1, top 25% height, prominent font, blacklist filtered
            String candidateName = null;
            if (!docModel.getPages().isEmpty()) {
                candidateName = extractCandidateName(docModel.getPages().get(0));
            }

            // 4. Column-aware block sorting & section segmentation
            Map<String, List<String>> sections = segmentSections(docModel);

            // 5. Extract full text from spatial layout for NLP & contact parsing
            String fullText = extractFullText(docModel);

            // 6. Contact extraction reusing PiiDetector & link matching
            ResumeExtractResponse.ContactInfo contact = extractContactInfo(fullText, annotationLinks);

            // 7. Dual-tier symbol-safe technical skill detection
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
            throw new ResumeParsingException(HttpStatus.UNPROCESSABLE_ENTITY, "Password-protected or encrypted PDFs are not supported", e);
        } catch (ResumeParsingException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to parse resume PDF: {}", e.getMessage(), e);
            throw new ResumeParsingException(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to parse resume PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Solves the multi-column interleaving problem by sorting blocks into reading order:
     * Full-Width Header Blocks -> Left Column Blocks -> Right Column Blocks.
     */
    public List<Block> sortBlocksByReadingOrder(List<Block> blocks, double pageWidth) {
        if (blocks == null || blocks.isEmpty()) {
            return Collections.emptyList();
        }

        List<Block> flatBlocks = unpackContainerBlocks(blocks);

        double midX = pageWidth > 0 ? pageWidth / 2.0 : 300.0;
        double tolerance = pageWidth > 0 ? pageWidth * 0.05 : 30.0;

        List<Block> fullWidthBlocks = new ArrayList<>();
        List<Block> leftColumnBlocks = new ArrayList<>();
        List<Block> rightColumnBlocks = new ArrayList<>();

        for (Block block : flatBlocks) {
            if (block == null) continue;
            double[] bbox = block.getBbox();
            if (bbox == null || bbox.length < 4) {
                fullWidthBlocks.add(block);
                continue;
            }

            double leftX = bbox[0];
            double width = bbox[2];
            double rightX = leftX + width;
            double blockWidth = width;
            if (bbox[2] > leftX && bbox[2] <= pageWidth && (bbox[2] - leftX) > blockWidth) {
                blockWidth = bbox[2] - leftX;
            }

            if (pageWidth > 0 && blockWidth > pageWidth * 0.70) {
                fullWidthBlocks.add(block);
            } else if (rightX <= midX + tolerance) {
                leftColumnBlocks.add(block);
            } else if (leftX >= midX - tolerance) {
                rightColumnBlocks.add(block);
            } else {
                double centerX = leftX + (width / 2.0);
                if (centerX < midX) {
                    leftColumnBlocks.add(block);
                } else {
                    rightColumnBlocks.add(block);
                }
            }
        }

        Comparator<Block> byY = Comparator.comparingDouble(b ->
                (b.getBbox() != null && b.getBbox().length > 1) ? b.getBbox()[1] : 0.0
        );
        fullWidthBlocks.sort(byY);
        leftColumnBlocks.sort(byY);
        rightColumnBlocks.sort(byY);

        List<Block> sorted = new ArrayList<>(fullWidthBlocks.size() + leftColumnBlocks.size() + rightColumnBlocks.size());
        sorted.addAll(fullWidthBlocks);
        sorted.addAll(leftColumnBlocks);
        sorted.addAll(rightColumnBlocks);

        return sorted;
    }

    private List<Block> unpackContainerBlocks(List<Block> blocks) {
        List<Block> result = new ArrayList<>();
        for (Block b : blocks) {
            if (b instanceof TableBlock tb) {
                if (tb.getCells() != null) {
                    for (List<ParagraphBlock> row : tb.getCells()) {
                        if (row != null) {
                            for (ParagraphBlock cell : row) {
                                if (cell != null) {
                                    result.add(cell);
                                }
                            }
                        }
                    }
                }
            } else {
                result.add(b);
            }
        }
        return result;
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
                continue; // Restrict search strictly to top 25% of Page 1
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
            return false; // Exclude numbers
        }

        if (text.contains("@") || text.contains("http") || text.contains("www.") ||
                text.contains("github") || text.contains("linkedin")) {
            return false; // Exclude email/URL strings
        }

        if (text.matches(".*[+()|/:;\\\\_=<>&*~•].*")) {
            return false; // Exclude phone punctuation & delimiters
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
            List<Block> rawBlocks = page.getBlocks();
            if (rawBlocks == null) continue;

            // Sort blocks column-aware to avoid section bleed between columns
            List<Block> sortedBlocks = sortBlocksByReadingOrder(rawBlocks, page.getWidth());

            for (Block block : sortedBlocks) {
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
        String combinedText = skillsSectionText + "\n" + (fullText != null ? fullText : "");

        // 1. Tier 1: Unambiguous skills (Case-Insensitive)
        for (Map.Entry<String, Pattern> entry : UNAMBIGUOUS_SKILL_PATTERNS.entrySet()) {
            String skill = entry.getKey();
            Pattern pattern = entry.getValue();
            if (pattern.matcher(combinedText).find()) {
                detected.add(canonicalizeSkill(skill));
            }
        }

        // 2. Tier 2: Case-sensitive skills (Exact case required)
        for (Map.Entry<String, Pattern> entry : CASE_SENSITIVE_SKILL_PATTERNS.entrySet()) {
            String skill = entry.getKey();
            if ("Spring".equals(skill) && detected.contains("Spring Boot")) {
                continue; // Do not emit redundant Spring if Spring Boot was already captured
            }
            if ("React".equals(skill) && detected.contains("React.js")) {
                continue;
            }
            Pattern pattern = entry.getValue();
            if (pattern.matcher(combinedText).find()) {
                detected.add(skill);
            }
        }

        // 3. Single-letter language safety: Standalone "C" and "R"
        if (C_LANGUAGE_PATTERN.matcher(combinedText).find()) {
            detected.add("C");
        }
        if (R_LANGUAGE_PATTERN.matcher(combinedText).find()) {
            detected.add("R");
        }

        return new ArrayList<>(detected);
    }

    private String canonicalizeSkill(String skill) {
        if ("spring boot".equalsIgnoreCase(skill)) return "Spring Boot";
        if ("postgresql".equalsIgnoreCase(skill)) return "PostgreSQL";
        if ("mysql".equalsIgnoreCase(skill)) return "MySQL";
        if ("mongodb".equalsIgnoreCase(skill)) return "MongoDB";
        if ("docker".equalsIgnoreCase(skill)) return "Docker";
        if ("kubernetes".equalsIgnoreCase(skill)) return "Kubernetes";
        if ("typescript".equalsIgnoreCase(skill)) return "TypeScript";
        if ("vue.js".equalsIgnoreCase(skill)) return "Vue.js";
        if ("node.js".equalsIgnoreCase(skill)) return "Node.js";
        if ("aws".equalsIgnoreCase(skill)) return "AWS";
        if ("gcp".equalsIgnoreCase(skill)) return "GCP";
        if ("redis".equalsIgnoreCase(skill)) return "Redis";
        if ("elasticsearch".equalsIgnoreCase(skill)) return "Elasticsearch";
        if ("kafka".equalsIgnoreCase(skill)) return "Kafka";
        if ("python".equalsIgnoreCase(skill)) return "Python";
        if ("golang".equalsIgnoreCase(skill)) return "Golang";
        if ("kotlin".equalsIgnoreCase(skill)) return "Kotlin";
        if ("swift".equalsIgnoreCase(skill)) return "Swift";
        if ("scala".equalsIgnoreCase(skill)) return "Scala";
        if ("php".equalsIgnoreCase(skill)) return "PHP";
        if ("ruby".equalsIgnoreCase(skill)) return "Ruby";
        if ("html".equalsIgnoreCase(skill) || "html5".equalsIgnoreCase(skill)) return "HTML";
        if ("css".equalsIgnoreCase(skill) || "css3".equalsIgnoreCase(skill)) return "CSS";
        if ("sql".equalsIgnoreCase(skill)) return "SQL";
        if (".net".equalsIgnoreCase(skill)) return ".NET";
        if ("asp.net".equalsIgnoreCase(skill)) return "ASP.NET";
        if ("django".equalsIgnoreCase(skill)) return "Django";
        if ("flask".equalsIgnoreCase(skill)) return "Flask";
        if ("fastapi".equalsIgnoreCase(skill)) return "FastAPI";
        if ("laravel".equalsIgnoreCase(skill)) return "Laravel";
        if ("tailwind css".equalsIgnoreCase(skill)) return "Tailwind CSS";
        if ("bootstrap".equalsIgnoreCase(skill)) return "Bootstrap";
        if ("hibernate".equalsIgnoreCase(skill)) return "Hibernate";
        if ("graphql".equalsIgnoreCase(skill)) return "GraphQL";
        if ("rest api".equalsIgnoreCase(skill)) return "REST API";
        if ("microservices".equalsIgnoreCase(skill)) return "Microservices";
        if ("ci/cd".equalsIgnoreCase(skill)) return "CI/CD";
        if ("jenkins".equalsIgnoreCase(skill)) return "Jenkins";
        if ("terraform".equalsIgnoreCase(skill)) return "Terraform";
        if ("ansible".equalsIgnoreCase(skill)) return "Ansible";
        if ("linux".equalsIgnoreCase(skill)) return "Linux";
        if ("azure".equalsIgnoreCase(skill)) return "Azure";
        if ("git".equalsIgnoreCase(skill)) return "Git";
        if ("github".equalsIgnoreCase(skill)) return "GitHub";
        if ("gitlab".equalsIgnoreCase(skill)) return "GitLab";
        if ("tensorflow".equalsIgnoreCase(skill)) return "TensorFlow";
        if ("pytorch".equalsIgnoreCase(skill)) return "PyTorch";
        if ("pandas".equalsIgnoreCase(skill)) return "Pandas";
        if ("numpy".equalsIgnoreCase(skill)) return "NumPy";
        if ("apache spark".equalsIgnoreCase(skill)) return "Apache Spark";
        if ("hadoop".equalsIgnoreCase(skill)) return "Hadoop";
        return skill;
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
