package com.aurify.fixclient.admin;

import com.aurify.fixclient.admin.dto.EnsureSessionRequest;
import com.aurify.fixclient.admin.dto.SessionStatusResponse;
import com.aurify.fixclient.session.DirectSessionControlService;
import com.aurify.fixclient.session.DynamicSessionManager;
import com.aurify.fixclient.session.LpSessionEntry;
import com.aurify.fixclient.session.LpSessionException;
import com.aurify.fixclient.session.LpSessionRegistry;
import com.aurify.fixclient.session.SessionRole;
import com.aurify.fixclient.session.SessionState;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Sessions are addressed by LP account, because a provider can now have many
 * of them - "the fxcubic session" is no longer a thing.
 *
 * This mirrors the gRPC session RPCs over plain HTTP so the gateway can be
 * driven from Postman or curl. It has no authentication and POST bodies carry
 * FIX passwords: keep the admin port off the network.
 */
@RestController
@RequestMapping("/admin/sessions")
@RequiredArgsConstructor
public class AdminSessionController {

    private final LpSessionRegistry sessionRegistry;
    private final DirectSessionControlService sessionControl;
    private final DynamicSessionManager sessionManager;

    @GetMapping
    public List<SessionStatusResponse> listSessions(@RequestParam(required = false) String provider) {
        return sessionRegistry.all().stream()
                .filter(entry -> provider == null || entry.provider().equals(provider))
                .map(this::toStatus)
                .toList();
    }

    /** Establishes (or verifies) a session from a supplied spec - the HTTP twin of EnsureSession. */
    @PostMapping
    public ResponseEntity<SessionStatusResponse> ensureSession(@RequestBody EnsureSessionRequest request) {
        try {
            LpSessionEntry entry = sessionManager.ensureSession(request.toSpec(), SessionRole.TRADING);
            return ResponseEntity.ok(toStatus(entry));
        } catch (LpSessionException e) {
            return ResponseEntity.status(502).body(SessionStatusResponse.builder()
                    .lpAccountId(request.lpAccountId())
                    .role(SessionRole.TRADING.name())
                    .state(SessionState.FAILED.name())
                    .loggedOn(false)
                    .errorCode(e.getCode())
                    .errorMessage(e.getMessage())
                    .build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(SessionStatusResponse.builder()
                    .lpAccountId(request.lpAccountId())
                    .state(SessionState.ABSENT.name())
                    .loggedOn(false)
                    .errorCode("LP_SESSION_SPEC_INVALID")
                    .errorMessage(e.getMessage())
                    .build());
        }
    }

    @GetMapping("/{lpAccountId}/{role}")
    public SessionStatusResponse status(@PathVariable String lpAccountId, @PathVariable SessionRole role) {
        return sessionRegistry.find(lpAccountId, role)
                .map(this::toStatus)
                .orElseGet(() -> SessionStatusResponse.builder()
                        .lpAccountId(lpAccountId)
                        .role(role.name())
                        .state(SessionState.ABSENT.name())
                        .loggedOn(false)
                        .build());
    }

    @PostMapping("/{lpAccountId}/{role}/logout")
    public void logout(@PathVariable String lpAccountId, @PathVariable SessionRole role,
                        @RequestParam(defaultValue = "Admin requested") String reason) {
        sessionRegistry.find(lpAccountId, role)
                .ifPresent(entry -> sessionControl.logout(entry.sessionId(), reason));
    }

    /** Drops the session entirely. The next order recreates it from its spec. */
    @DeleteMapping("/{lpAccountId}/{role}")
    public boolean close(@PathVariable String lpAccountId, @PathVariable SessionRole role) {
        return sessionManager.closeSession(lpAccountId, role);
    }

    private SessionStatusResponse toStatus(LpSessionEntry entry) {
        var snapshot = sessionControl.statusOf(entry.sessionId());
        return SessionStatusResponse.builder()
                .lpAccountId(entry.lpAccountId())
                .provider(entry.provider())
                .role(entry.role().name())
                .state(entry.state().name())
                .sessionId(entry.sessionId().toString())
                .specFingerprint(entry.specFingerprint())
                .loggedOn(snapshot.isLoggedOn())
                .nextInboundSeqNum(snapshot.getNextInboundSeqNum())
                .nextOutboundSeqNum(snapshot.getNextOutboundSeqNum())
                .loggedOnAtEpochMs(entry.loggedOnAtEpochMs())
                .lastUsedAtEpochMs(entry.lastUsedAtEpochMs())
                .build();
    }
}
