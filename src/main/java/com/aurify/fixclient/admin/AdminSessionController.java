package com.aurify.fixclient.admin;

import com.aurify.fixclient.admin.dto.SessionStatusResponse;
import com.aurify.fixclient.session.DirectSessionControlService;
import com.aurify.fixclient.session.ProviderSessionRegistry;
import com.aurify.fixclient.session.SessionRole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import quickfix.SessionID;

import java.util.List;

@RestController
@RequestMapping("/admin/sessions")
@RequiredArgsConstructor
public class AdminSessionController {

    private final ProviderSessionRegistry sessionRegistry;
    private final DirectSessionControlService sessionControl;

    @GetMapping
    public List<SessionStatusResponse> listSessions(@RequestParam(required = false) String provider) {
        List<SessionID> sessions = provider != null
                ? sessionRegistry.allSessionsFor(provider)
                : List.of(); // in a full impl, iterate all known providers
        return sessions.stream().map(this::toStatus).toList();
    }

    @PostMapping("/{provider}/{role}/logon")
    public void logon(@PathVariable String provider, @PathVariable SessionRole role) {
        sessionRegistry.resolve(provider, role).ifPresent(sessionControl::logon);
    }

    @PostMapping("/{provider}/{role}/logout")
    public void logout(@PathVariable String provider, @PathVariable SessionRole role,
                        @RequestParam(defaultValue = "Admin requested") String reason) {
        sessionRegistry.resolve(provider, role).ifPresent(s -> sessionControl.logout(s, reason));
    }

    @PostMapping("/{provider}/{role}/stop")
    public void stop(@PathVariable String provider, @PathVariable SessionRole role,
                      @RequestParam(defaultValue = "false") boolean force) {
        sessionRegistry.resolve(provider, role).ifPresent(s -> sessionControl.stop(s, force));
    }

    private SessionStatusResponse toStatus(SessionID sessionId) {
        var snapshot = sessionControl.statusOf(sessionId);
        return SessionStatusResponse.builder()
                .sessionId(sessionId.toString())
                .loggedOn(snapshot.isLoggedOn())
                .nextInboundSeqNum(snapshot.getNextInboundSeqNum())
                .nextOutboundSeqNum(snapshot.getNextOutboundSeqNum())
                .build();
    }
}
