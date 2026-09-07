package com.seatflow.ai.service.seat;

import com.seatflow.ai.client.dto.PricingTierClientDto;
import com.seatflow.ai.service.seat.SeatPriceResolver.PriceResolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SeatPriceResolverTest {

    private static PricingTierClientDto tier(String category, String price, String currency) {
        return new PricingTierClientDto(UUID.randomUUID(), UUID.randomUUID(), category,
                price == null ? null : new BigDecimal(price), currency);
    }

    @Test
    @DisplayName("no requested category resolves the canonical default: the first (lowest) tier")
    void resolvesCanonicalDefault() {
        UUID tierId = UUID.randomUUID();
        List<PricingTierClientDto> tiers = List.of(
                new PricingTierClientDto(tierId, UUID.randomUUID(), "Standard",
                        new BigDecimal("25.00"), "EUR"),
                tier("VIP", "80.00", "EUR"));

        PriceResolution resolution = SeatPriceResolver.resolve(tiers, null, null);

        assertThat(resolution).isInstanceOf(PriceResolution.Resolved.class);
        PriceResolution.Resolved resolved = (PriceResolution.Resolved) resolution;
        assertThat(resolved.pricingTierId()).isEqualTo(tierId);
        assertThat(resolved.priceMinor()).isEqualTo(2500L);
        assertThat(resolved.currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("requested category matches exactly, ignoring case and padding")
    void resolvesExactCategoryMatch() {
        List<PricingTierClientDto> tiers = List.of(
                tier("Standard", "25.00", "EUR"),
                tier("VIP", "80.00", "EUR"));

        PriceResolution resolution = SeatPriceResolver.resolve(tiers, "  vip ", null);

        assertThat(resolution).isInstanceOfSatisfying(PriceResolution.Resolved.class,
                resolved -> assertThat(resolved.priceMinor()).isEqualTo(8000L));
    }

    @Test
    @DisplayName("requested category without a match leaves the seat unpriceable")
    void unknownCategoryIsUnpriceable() {
        List<PricingTierClientDto> tiers = List.of(tier("Standard", "25.00", "EUR"));

        assertThat(SeatPriceResolver.resolve(tiers, "Balcony", null))
                .isInstanceOf(PriceResolution.Unpriceable.class);
    }

    @Test
    @DisplayName("multiple tiers but no valid price require explicit selection, never a silent pick")
    void ambiguousTiersRequireSelection() {
        List<PricingTierClientDto> tiers = List.of(
                tier("Standard", null, "EUR"),
                tier("VIP", "0.00", "EUR"));

        PriceResolution resolution = SeatPriceResolver.resolve(tiers, null, null);

        assertThat(resolution).isInstanceOfSatisfying(PriceResolution.SelectionRequired.class,
                selection -> assertThat(selection.availableCategories())
                        .containsExactlyInAnyOrder("Standard", "VIP"));
    }

    @Test
    @DisplayName("one requested category matching several tiers requires explicit selection")
    void duplicateCategoryMatchRequiresSelection() {
        List<PricingTierClientDto> tiers = List.of(
                tier("VIP", "80.00", "EUR"),
                tier("vip", "85.00", "EUR"));

        assertThat(SeatPriceResolver.resolve(tiers, "VIP", null))
                .isInstanceOf(PriceResolution.SelectionRequired.class);
    }

    @Test
    @DisplayName("tiers in another currency are never eligible")
    void filtersCurrency() {
        List<PricingTierClientDto> tiers = List.of(tier("Standard", "25.00", "USD"));

        assertThat(SeatPriceResolver.resolve(tiers, null, "EUR"))
                .isInstanceOf(PriceResolution.Unpriceable.class);
        assertThat(SeatPriceResolver.resolve(tiers, null, "usd"))
                .isInstanceOf(PriceResolution.Resolved.class);
    }

    @Test
    @DisplayName("empty tier list leaves the seat unpriceable")
    void emptyTiersAreUnpriceable() {
        assertThat(SeatPriceResolver.resolve(List.of(), null, null))
                .isInstanceOf(PriceResolution.Unpriceable.class);
        assertThat(SeatPriceResolver.resolve(null, null, null))
                .isInstanceOf(PriceResolution.Unpriceable.class);
    }
}
