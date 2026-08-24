package com.aurify.fixclient.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "canonical_event")
@Getter
@Setter
public class CanonicalEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String eventType;
    private String provider;
    @Lob
    private String payloadJson;
    private String correlationId;
    private Instant createdAt;
}
