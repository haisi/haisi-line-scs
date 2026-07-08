package li.selman.optimisticlocking.line.web;

import jakarta.validation.constraints.NotBlank;

public record CreateLineRequest(int left, int right, @NotBlank String businessPartnerId) {}
