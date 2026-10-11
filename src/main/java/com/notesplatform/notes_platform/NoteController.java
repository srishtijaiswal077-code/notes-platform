package com.notesplatform.notes_platform;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Notes / PYQ sharing. Every endpoint needs a face-login session (set by /api/face/login-live). */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private static final Path UPLOAD_DIR = Paths.get("uploads").toAbsolutePath().normalize();
    private static final Set<String> ALLOWED_EXT = Set.of("pdf", "png", "jpg", "jpeg", "docx", "pptx");
    private static final Set<String> KINDS = Set.of("NOTES", "PYQ");

    private final NoteRepository repo;

    public NoteController(NoteRepository repo) {
        this.repo = repo;
    }

    private Long loggedInUser(HttpSession session) {
        Object id = session.getAttribute("userId");
        return id instanceof Long ? (Long) id : null;
    }

    private ResponseEntity<?> notLoggedIn() {
        return ResponseEntity.status(401).body(Map.of("error", "Please log in with your face first"));
    }

    /** Used by notes.html to find out who is logged in. */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpSession session) {
        Long userId = loggedInUser(session);
        if (userId == null) return notLoggedIn();
        return ResponseEntity.ok(Map.of("userId", userId));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam String title,
                                    @RequestParam String subject,
                                    @RequestParam String kind,
                                    @RequestParam("file") MultipartFile file,
                                    HttpSession session) throws IOException {
        Long userId = loggedInUser(session);
        if (userId == null) return notLoggedIn();

        if (title.isBlank() || subject.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Title and subject are required"));
        }
        if (!KINDS.contains(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Type must be NOTES or PYQ"));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please choose a file"));
        }

        // keep only the file name (no folders), and allow only known extensions
        String original = StringUtils.getFilename(StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename()));
        if (original == null || !original.contains(".")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid file name"));
        }
        String ext = original.substring(original.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_EXT.contains(ext)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Allowed files: " + ALLOWED_EXT));
        }

        // random name on disk, so the user's file name can never point outside the uploads folder
        String storedName = UUID.randomUUID() + "." + ext;
        Files.createDirectories(UPLOAD_DIR);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, UPLOAD_DIR.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
        }

        Note note = new Note();
        note.setTitle(title.trim());
        note.setSubject(subject.trim());
        note.setKind(kind);
        note.setOriginalName(original);
        note.setStoredName(storedName);
        note.setUploaderId(userId);
        repo.save(note);

        return ResponseEntity.ok(Map.of("message", "Uploaded", "id", note.getId()));
    }

    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        if (loggedInUser(session) == null) return notLoggedIn();

        List<Map<String, Object>> out = repo.findAllByOrderByUploadedAtDesc().stream()
                .map(n -> Map.<String, Object>of(
                        "id", n.getId(),
                        "title", n.getTitle(),
                        "subject", n.getSubject(),
                        "kind", n.getKind(),
                        "fileName", n.getOriginalName(),
                        "uploaderId", n.getUploaderId(),
                        "uploadedAt", n.getUploadedAt().toString()))
                .toList();
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable Long id, HttpSession session) {
        if (loggedInUser(session) == null) return notLoggedIn();

        Note note = repo.findById(id).orElse(null);
        if (note == null) return ResponseEntity.notFound().build();

        Path path = UPLOAD_DIR.resolve(note.getStoredName()).normalize();
        if (!path.startsWith(UPLOAD_DIR) || !Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        Resource body = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(note.getOriginalName(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(body);
    }
}