package org.meldtech.platform.conformance.fixtures.r1.consumer;

import org.meldtech.platform.conformance.fixtures.r1.slice.target.SliceType;

public final class IllegalSliceImport {

    private final SliceType sliceType = new SliceType();

    public SliceType sliceType() {
        return sliceType;
    }
}
