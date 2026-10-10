package com.github.vad_ik.imadjOnline.db.repository;

import com.github.vad_ik.imadjOnline.db.entity.Card;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CardRepository extends JpaRepository<Card, Long> {
    List<Card> findByDeckIdOrderByIdAsc(Long deckId);

    Page<Card> findByDeckIdOrderByIdAsc(Long deckId, Pageable pageable);

    List<Card> findByDeckIdInOrderByIdAsc(Collection<Long> deckIds);

    Optional<Card> findByIdAndDeckId(Long id, Long deckId);

    long countByDeckId(Long deckId);
}
