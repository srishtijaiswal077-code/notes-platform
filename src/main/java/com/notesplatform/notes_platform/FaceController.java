package com.notesplatform.notes_platform;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/face")
@CrossOrigin(origins = "http://localhost:5173")
public class FaceController {

    private static final double THRESHOLD = 0.363;

    private final FaceService faceService;
    private final FaceDataRepository repo;

    public FaceController(FaceService faceService, FaceDataRepository repo) {
        this.faceService = faceService;
        this.repo = repo;
    }

    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(@RequestParam Long userId,
                                    @RequestParam("image") MultipartFile image) throws IOException {
        float[] embedding;
        try {
            embedding = faceService.getEmbedding(image.getBytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        // duplicate check: kisi doosre user se chehra match to nahi karta?
        for (FaceData other : repo.findByUserIdNot(userId)) {
            double score = faceService.compare(embedding, other.getEmbedding());
            if (score >= THRESHOLD) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "Ye chehra pehle se kisi aur account se registered hai",
                        "score", score));
            }
        }

        FaceData data = repo.findByUserId(userId).orElse(new FaceData());
        data.setUserId(userId);
        data.setEmbedding(embedding);
        repo.save(data);

        return ResponseEntity.ok(Map.of("message", "Face enrolled", "userId", userId));
    }
}