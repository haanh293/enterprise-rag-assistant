package com.haanh.ragassistant.repository;

import com.haanh.ragassistant.entity.DocumentMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentMetadata, UUID> {
    Optional<DocumentMetadata> findByFilename(String filename);
    List<DocumentMetadata> findAllByOrderByCreatedAtDesc();
}
