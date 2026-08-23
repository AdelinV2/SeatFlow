package com.seatflow.common.domain.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PagedResultTest {

    @Test
    void shouldComputeTotalPagesAndFlagsForFullPage() {
        List<Integer> content = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        PagedResult<Integer> result = PagedResult.of(content, 0, 10, 25);

        assertThat(result.content()).hasSize(10);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(25);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.isFirst()).isTrue();
        assertThat(result.isLast()).isFalse();
    }

    @Test
    void shouldMarkLastPageCorrectly() {
        List<Integer> content = List.of(1, 2, 3, 4, 5);
        PagedResult<Integer> result = PagedResult.of(content, 2, 10, 25);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.isFirst()).isFalse();
        assertThat(result.isLast()).isTrue();
    }

    @Test
    void shouldHandleEmptyContent() {
        PagedResult<Integer> result = PagedResult.of(List.of(), 0, 10, 0);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.totalPages()).isZero();
        assertThat(result.isFirst()).isTrue();
        assertThat(result.isLast()).isTrue();
    }

    @Test
    void shouldHandleZeroSizeWithoutDivisionByZero() {
        PagedResult<Integer> result = PagedResult.of(List.of(1), 0, 0, 5);

        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.isLast()).isTrue();
    }

    @Test
    void shouldDefensivelyCopyNullContent() {
        PagedResult<Integer> result = PagedResult.of(null, 0, 10, 0);

        assertThat(result.content()).isEmpty();
    }

    @Test
    void shouldNotAllowExternalMutationOfContent() {
        List<Integer> source = new ArrayList<>(List.of(1, 2));
        PagedResult<Integer> result = PagedResult.of(source, 0, 10, 2);

        assertThat(result.content()).isNotSameAs(source);
        source.add(3);
        assertThat(result.content()).doesNotContain(3);
    }
}
