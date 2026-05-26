package com.dailytools.s3migration.job;

import com.dailytools.s3migration.config.MigrationProperties;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class WatermarkPolicy {

    private final MigrationProperties properties;

    public WatermarkPolicy(MigrationProperties properties) {
        this.properties = properties;
    }

    public Instant initialAfterBaseline(ProcessingSummary baselineSummary) {
        Instant configured = properties.getJob().getInitialWatermark();
        if (configured != null) {
            return configured;
        }
        Instant observed = baselineSummary.maxLastModified();
        return observed == null ? Instant.EPOCH : applyLookback(observed);
    }

    public Instant initialForDeltaWhenStateMissing() {
        Instant configured = properties.getJob().getInitialWatermark();
        return configured == null ? Instant.EPOCH : configured;
    }

    public Instant advanceAfterDelta(Instant previousWatermark, ProcessingSummary deltaSummary) {
        Instant observed = deltaSummary.maxLastModified();
        if (observed != null) {
            Instant candidate = applyLookback(observed);
            if (candidate.isAfter(previousWatermark)) {
                return candidate;
            }
        }
        return previousWatermark;
    }

    private Instant applyLookback(Instant observed) {
        return observed.minus(properties.getJob().getDeltaLookback());
    }
}
