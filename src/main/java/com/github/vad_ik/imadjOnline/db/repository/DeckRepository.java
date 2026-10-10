package com.github.vad_ik.imadjOnline.db.repository;

import com.github.vad_ik.imadjOnline.db.entity.Deck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DeckRepository extends JpaRepository<Deck, Long> {
    List<Deck> findByIsPublicTrueOrderByIdAsc();

    List<Deck> findAllByOrderByIdAsc();

    List<Deck> findByOwnerIdOrderByIdAsc(Long ownerId);

    @Query("""
            select d from Deck d
            where d.isPublic = true
               or d.ownerId = :userId
            order by d.id asc
            """)
    List<Deck> findVisibleToUser(@Param("userId") Long userId);

    @Query("""
            select d from Deck d
            where d.isPublic = true
               or (d.ownerId is not null and d.ownerId in :ownerIds)
            order by d.id asc
            """)
    List<Deck> findPublicOrOwnedBy(@Param("ownerIds") List<Long> ownerIds);
}
