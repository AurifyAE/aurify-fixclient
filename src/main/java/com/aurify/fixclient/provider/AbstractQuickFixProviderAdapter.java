package com.aurify.fixclient.provider;

import com.aurify.fixclient.canonical.event.CanonicalOutboundRequest;
import com.aurify.fixclient.events.GatewayEventPublisher;
import quickfix.Message;
import quickfix.field.SendingTime;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Shared, provider-agnostic plumbing only. Anything LP-specific belongs in
 * the concrete subclass (e.g. FxCubicProviderAdapter), never here.
 */
public abstract class AbstractQuickFixProviderAdapter implements LiquidityProviderAdapter {

    protected final GatewayEventPublisher eventPublisher;

    protected AbstractQuickFixProviderAdapter(GatewayEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    protected final void applyStandardHeader(Message message) {
        message.getHeader().setField(new SendingTime(LocalDateTime.now(ZoneOffset.UTC)));
    }

    @Override
    public ValidationResult validateOutbound(CanonicalOutboundRequest request, OutboundPolicy policy) {
        return ValidationResult.ok(); // override per provider where LP rules apply
    }
}
