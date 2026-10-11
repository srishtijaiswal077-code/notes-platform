package com.notesplatform.notes_platform;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Head-turn liveness check.
 * Idea: when looking straight, the nose x is close to the midpoint between the eyes.
 * Turning the head shifts the nose sideways. We divide that shift by the eye distance,
 * so the value does not depend on how far the user sits from the camera.
 *
 * Measured on real captures (frame NOT mirrored):
 *   straight ~ 0, user's left ~ positive, user's right ~ negative.
 */
@Service
public class LivenessService {

    public enum Challenge { LEFT, RIGHT }

    private record Pending(Challenge challenge, long expiresAt) {}

    private static final double CENTER_MAX = 0.2;   // first frame must be (almost) straight
    private static final double TURN_MIN = 0.25;    // minimum shift in the requested direction
    private static final long CHALLENGE_TTL_MS = 60_000;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public Challenge newChallenge() {
        return random.nextBoolean() ? Challenge.LEFT : Challenge.RIGHT;
    }

    /** Stores the challenge under a one-time token (expires after 60 seconds). */
    public String issue(Challenge challenge) {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expiresAt() < now);   // clean up old ones
        String token = UUID.randomUUID().toString();
        pending.put(token, new Pending(challenge, now + CHALLENGE_TTL_MS));
        return token;
    }

    /** Returns the challenge for this token and deletes it, so a token works only once. */
    public Challenge consume(String token) {
        Pending p = pending.remove(token);
        if (p == null || p.expiresAt() < System.currentTimeMillis()) return null;
        return p.challenge();
    }

    /** yaw ratio from one YuNet detection row (15 floats: box, 5 landmarks, score). */
    public double yaw(float[] faceRow) {
        double rightEyeX = faceRow[4], rightEyeY = faceRow[5];
        double leftEyeX = faceRow[6], leftEyeY = faceRow[7];
        double noseX = faceRow[8];

        double eyeDist = Math.hypot(leftEyeX - rightEyeX, leftEyeY - rightEyeY);
        if (eyeDist < 1e-6) return Double.NaN;

        double eyeMidX = (rightEyeX + leftEyeX) / 2.0;
        return (noseX - eyeMidX) / eyeDist;
    }

    /** Center frame must be straight, and the turn frame must move in the requested direction. */
    public boolean verify(Challenge challenge, double centerYaw, double turnYaw) {
        if (Double.isNaN(centerYaw) || Double.isNaN(turnYaw)) return false;
        if (Math.abs(centerYaw) > CENTER_MAX) return false;
        double shift = turnYaw - centerYaw;
        return challenge == Challenge.LEFT ? shift >= TURN_MIN : shift <= -TURN_MIN;
    }
}

