package com.aurify.fixclient.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "raw_fix_message")
@Getter
@Setter
public class RawFixMessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sessionId;
    private String direction;
    private String provider;
    @Lob
    private String rawText;
    private Instant receivedAt;
}
