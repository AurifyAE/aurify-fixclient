package com.aurify.fixclient.admin;

import com.aurify.fixclient.admin.dto.SequenceResetRequest;
import com.aurify.fixclient.session.DirectSessionControlService;
import com.aurify.fixclient.session.LpSessionRegistry;
import com.aurify.fixclient.session.SessionRole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/sessions/{lpAccountId}/{role}/sequence")
@RequiredArgsConstructor
public class AdminSequenceController {

    private final LpSessionRegistry sessionRegistry;
    private final DirectSessionControlService sessionControl;

    @PostMapping
    public void resetSequence(@PathVariable String lpAccountId, @PathVariable SessionRole role,
                               @RequestBody SequenceResetRequest request) {
        sessionRegistry.find(lpAccountId, role).ifPresent(entry -> {
            sessionControl.setNextInboundSeqNum(entry.sessionId(), request.nextInboundSeqNum());
            sessionControl.setNextOutboundSeqNum(entry.sessionId(), request.nextOutboundSeqNum());
        });
    }
}
