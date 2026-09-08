package com.seatflow.ai.service.seat;

import com.seatflow.ai.client.dto.PricingTierClientDto;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Per-seat pricing-tier selection (TASK-P15-003 section 9).
 *
 * <p>SeatFlow may expose multiple pricing tiers per section; the AI service never guesses a tier:
 *
 * <ul>
 *   <li>when the caller requests a category, only exact normalized matches
 *       ({@code trim} + case-insensitive) are eligible;</li>
 *   <li>otherwise the canonical default applies: the section's first tier, which the Event Service
 *       returns ordered by ascending price and which the normal reservation flow uses as its
 *       section price ({@code pricingTiers.getFirst().price()});</li>
 *   <li>when several tiers are possible but no canonical default exists (no tier carries a valid
 *       positive price, or one requested category matches several tiers), selection is refused
 *       explicitly instead of choosing silently;</li>
 *   <li>tiers in a different currency than requested are never eligible and never summed.</li>
 * </ul>
 */
public final class SeatPriceResolver {

    private SeatPriceResolver() {
    }

    public sealed interface PriceResolution
            permits PriceResolution.Resolved, PriceResolution.SelectionRequired, PriceResolution.Unpriceable {

        record Resolved(UUID pricingTierId, long priceMinor, String currency, String categoryName)
                implements PriceResolution {}

        record SelectionRequired(List<String> availableCategories) implements PriceResolution {}

        record Unpriceable() implements PriceResolution {}
    }

    /**
     * Resolve the price for one seat's section.
     *
     * @param tiers section pricing tiers in upstream order (ascending price)
     * @param preferredCategory caller-requested category, or {@code null} for the default
     * @param requestedCurrency caller-requested ISO currency, or {@code null} for any
     */
    public static PriceResolution resolve(
            List<PricingTierClientDto> tiers, String preferredCategory, String requestedCurrency) {
        List<PricingTierClientDto> currencyEligible = tiers == null ? List.of()
                : tiers.stream()
                        .filter(tier -> tier != null && currencyMatches(tier.currency(), requestedCurrency))
                        .toList();
        if (preferredCategory != null) {
            String wanted = preferredCategory.trim();
            List<PricingTierClientDto> matches = currencyEligible.stream()
                    .filter(tier -> tier.categoryName() != null
                            && tier.categoryName().trim().equalsIgnoreCase(wanted))
                    .toList();
            if (matches.isEmpty()) {
                return new PriceResolution.Unpriceable();
            }
            if (matches.size() > 1) {
                return new PriceResolution.SelectionRequired(distinctCategories(currencyEligible));
            }
            return toResolved(matches.getFirst()).orElseGet(PriceResolution.Unpriceable::new);
        }
        List<PricingTierClientDto> valid =
                currencyEligible.stream().filter(tier -> priceMinor(tier).isPresent()).toList();
        if (!valid.isEmpty()) {
            return toResolved(valid.getFirst()).orElseGet(PriceResolution.Unpriceable::new);
        }
        if (!currencyEligible.isEmpty()) {
            return new PriceResolution.SelectionRequired(distinctCategories(currencyEligible));
        }
        return new PriceResolution.Unpriceable();
    }

    private static java.util.Optional<PriceResolution> toResolved(PricingTierClientDto tier) {
        OptionalLong minor = priceMinor(tier);
        if (minor.isEmpty() || tier.id() == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new PriceResolution.Resolved(
                tier.id(), minor.getAsLong(), tier.currency(), tier.categoryName()));
    }

    private static OptionalLong priceMinor(PricingTierClientDto tier) {
        if (tier == null || tier.price() == null || tier.price().signum() <= 0) {
            return OptionalLong.empty();
        }
        return MoneyMinor.tryToMinor(tier.price());
    }

    private static boolean currencyMatches(String tierCurrency, String requestedCurrency) {
        if (requestedCurrency == null) {
            return true;
        }
        return tierCurrency != null && tierCurrency.trim().equalsIgnoreCase(requestedCurrency.trim());
    }

    private static List<String> distinctCategories(List<PricingTierClientDto> tiers) {
        return tiers.stream()
                .map(PricingTierClientDto::categoryName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }
}
