package com.notesplatform.notes_platform;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FaceDataRepository extends JpaRepository<FaceData, Long> {
    Optional<FaceData> findByUserId(Long userId);
    List<FaceData> findByUserIdNot(Long userId);
}