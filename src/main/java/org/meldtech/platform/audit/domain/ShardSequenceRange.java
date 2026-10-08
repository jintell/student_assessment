package org.meldtech.platform.audit.domain;

import java.util.OptionalLong;

public record ShardSequenceRange(int shardId, OptionalLong start, OptionalLong end) {}
