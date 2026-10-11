package com.notesplatform.notes_platform;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/face")
@CrossOrigin(origins = "http://localhost:5173")
public class FaceController {

    private static final double THRESHOLD = 0.363;          // same person across different accounts
    private static final double LOGIN_THRESHOLD = 0.363;    // same person, same account (login)
    private static final double SAME_PERSON_MIN = 0.30;     // straight frame vs turned frame (a turned face scores lower)

    private final FaceService faceService;
    private final FaceDataRepository repo;
    private final LivenessService livenessService;

    public FaceController(FaceService faceService,
                          FaceDataRepository repo,
                          LivenessService livenessService) {
        this.faceService = faceService;
        this.repo = repo;
        this.livenessService = livenessService;
    }

    /** Step 1 of enrolling / login: server picks a random challenge (LEFT or RIGHT) and gives a one-time token. */
    @GetMapping("/liveness/challenge")
    public Map<String, String> challenge() {
        LivenessService.Challenge c = livenessService.newChallenge();
        String token = livenessService.issue(c);
        return Map.of("token", token, "challenge", c.name());
    }

    /** Step 2 of enrolling: straight photo + turned photo + token. Enrolls only if everything checks out. */
    @PostMapping("/enroll-live")
    public ResponseEntity<?> enrollLive(@RequestParam Long userId,
                                        @RequestParam String token,
                                        @RequestParam("center") MultipartFile center,
                                        @RequestParam("turn") MultipartFile turn) throws IOException {

        LivenessService.Challenge challenge = livenessService.consume(token);
        if (challenge == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Challenge expired. Please start again."));
        }

        FaceService.FaceResult first, second;
        try {
            first = faceService.analyze(center.getBytes());
            second = faceService.analyze(turn.getBytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        // both photos must show the same person (stops swapping in another face for the turn)
        double same = faceService.compare(first.embedding(), second.embedding());
        System.out.printf("same-person score = %.3f%n", same);
        if (same < SAME_PERSON_MIN) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Both photos must show the same person",
                    "score", same));
        }

        if (!livenessService.verify(challenge, first.yaw(), second.yaw())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Liveness check failed. Turn your head clearly to your "
                            + challenge.name().toLowerCase() + "."));
        }

        // only after liveness passes: is this user ID already enrolled?
        if (repo.findByUserId(userId).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "Face already enrolled for this user"));
        }

        // duplicate check against every other account (uses the straight photo)
        for (FaceData other : repo.findByUserIdNot(userId)) {
            double score = faceService.compare(first.embedding(), other.getEmbedding());
            if (score >= THRESHOLD) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "Ye chehra pehle se kisi aur account se registered hai",
                        "score", score));
            }
        }

        FaceData data = new FaceData();
        data.setUserId(userId);
        data.setEmbedding(first.embedding());
        repo.save(data);

        return ResponseEntity.ok(Map.of("message", "Face enrolled", "userId", userId));
    }

    /** Face login: straight photo + turned photo + token + userId. 1-to-1 match against that user's stored face. */
    @PostMapping("/login-live")
    public ResponseEntity<?> loginLive(@RequestParam Long userId,
                                       @RequestParam String token,
                                       @RequestParam("center") MultipartFile center,
                                       @RequestParam("turn") MultipartFile turn,
                                       HttpSession session) throws IOException {

        LivenessService.Challenge challenge = livenessService.consume(token);
        if (challenge == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Challenge expired. Please start again."));
        }

        FaceService.FaceResult first, second;
        try {
            first = faceService.analyze(center.getBytes());
            second = faceService.analyze(turn.getBytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        double same = faceService.compare(first.embedding(), second.embedding());
        if (same < SAME_PERSON_MIN) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Both photos must show the same person",
                    "score", same));
        }

        if (!livenessService.verify(challenge, first.yaw(), second.yaw())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Liveness check failed. Turn your head clearly to your "
                            + challenge.name().toLowerCase() + "."));
        }

        // liveness passed, only now look at the account.
        // Same message for "no such user" and "face mismatch" so IDs cannot be probed.
        Optional<FaceData> stored = repo.findByUserId(userId);
        if (stored.isEmpty()
                || faceService.compare(first.embedding(), stored.get().getEmbedding()) < LOGIN_THRESHOLD) {
            return ResponseEntity.status(401).body(Map.of("error", "Face login failed"));
        }

        session.setAttribute("userId", userId);
        return ResponseEntity.ok(Map.of("message", "Login successful", "userId", userId));
    }
}