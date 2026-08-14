package com.tj.crypto.backtest.robustness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurgedEmbargoSplitGeneratorTest {
    private final PurgedEmbargoSplitGenerator generator = new PurgedEmbargoSplitGenerator();

    @Test
    void generatesDeterministicContiguousPurgedAndEmbargoedFolds() {
        List<PurgedEmbargoSplit> splits = generator.generate(20, 4, 2, 1);

        assertThat(splits).hasSize(4).isEqualTo(generator.generate(20, 4, 2, 1));
        PurgedEmbargoSplit second = splits.get(1);
        assertThat(second.validationRange()).isEqualTo(new IndexRange(5, 10));
        assertThat(second.trainingRanges()).containsExactly(new IndexRange(0, 3), new IndexRange(13, 20));
        assertThat(second.purgedRanges()).containsExactly(new IndexRange(3, 5), new IndexRange(10, 12));
        assertThat(second.embargoRange()).isEqualTo(new IndexRange(12, 13));

        assertThat(splits.get(0).trainingRanges()).containsExactly(new IndexRange(8, 20));
        assertThat(splits.get(3).trainingRanges()).containsExactly(new IndexRange(0, 13));
    }

    @Test
    void neverPlacesValidationPurgeOrEmbargoObservationsInTraining() {
        List<PurgedEmbargoSplit> splits = generator.generate(23, 5, 2, 3);

        for (PurgedEmbargoSplit split : splits) {
            for (IndexRange training : split.trainingRanges()) {
                for (int index = training.startInclusive(); index < training.endExclusive(); index++) {
                    int observationIndex = index;
                    assertThat(split.validationRange().contains(index)).isFalse();
                    assertThat(split.purgedRanges().stream()
                            .anyMatch(range -> range.contains(observationIndex))).isFalse();
                    assertThat(split.embargoRange().contains(index)).isFalse();
                }
            }
        }
    }

    @Test
    void returnsImmutableRangeCollections() {
        PurgedEmbargoSplit split = generator.generate(20, 4, 1, 1).get(1);

        assertThatThrownBy(() -> split.trainingRanges().add(new IndexRange(0, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> split.purgedRanges().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesCountsAndRejectsUnusableSplits() {
        assertThatThrownBy(() -> generator.generate(1, 2, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observationCount");
        assertThatThrownBy(() -> generator.generate(10, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("foldCount");
        assertThatThrownBy(() -> generator.generate(10, 2, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purgeSize");
        assertThatThrownBy(() -> generator.generate(10, 2, 4, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no training observations");
    }
}
