package com.seatflow.event.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "A single pricing tier item within a ConfigurePricingRequest")
public record PricingTierItemRequest(

    @Schema(description = "Target section UUID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Section ID is required")
    UUID sectionId,

    @Schema(description = "Pricing category name (e.g. 'VIP', 'General')", example = "VIP", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Category name is required")
    @Size(max = 100, message = "Category name must not exceed 100 characters")
    String categoryName,

    @Schema(description = "Price amount", example = "129.90", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price must be zero or greater")
    @Digits(integer = 8, fraction = 2, message = "Price must have at most 8 integer and 2 fractional digits")
    BigDecimal price,

    @Schema(description = "Uppercase ISO-4217 currency code", example = "USD", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an uppercase ISO-4217 code")
    String currency

) {}
