package com.github.vad_ik.imadjOnline.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "decks")
@Getter
@Setter
public class Deck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = true;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
