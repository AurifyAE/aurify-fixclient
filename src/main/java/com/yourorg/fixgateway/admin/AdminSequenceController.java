package com.yourorg.fixgateway.admin;

import com.yourorg.fixgateway.admin.dto.SequenceResetRequest;
import com.yourorg.fixgateway.session.DirectSessionControlService;
import com.yourorg.fixgateway.session.ProviderSessionRegistry;
import com.yourorg.fixgateway.session.SessionRole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/sessions/{provider}/{role}/sequence")
@RequiredArgsConstructor
public class AdminSequenceController {

    private final ProviderSessionRegistry sessionRegistry;
    private final DirectSessionControlService sessionControl;

    @PostMapping
    public void resetSequence(@PathVariable String provider, @PathVariable SessionRole role,
                               @RequestBody SequenceResetRequest request) {
        sessionRegistry.resolve(provider, role).ifPresent(sessionId -> {
            sessionControl.setNextInboundSeqNum(sessionId, request.nextInboundSeqNum());
            sessionControl.setNextOutboundSeqNum(sessionId, request.nextOutboundSeqNum());
        });
    }
}
