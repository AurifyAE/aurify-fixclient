package com.aurify.fixclient.admin;

import com.aurify.fixclient.provider.LiquidityProviderAdapter;
import com.aurify.fixclient.provider.ProviderAdapterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/providers")
@RequiredArgsConstructor
public class AdminProviderController {

    private final ProviderAdapterRegistry adapterRegistry;

    @GetMapping("/{provider}/capabilities")
    public Object capabilities(@PathVariable String provider) {
        return adapterRegistry.resolve(provider)
                .map(LiquidityProviderAdapter::capabilities)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + provider));
    }
}
