package org.meldtech.platform.audit.domain;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.meldtech.platform.shared.kernel.identity.TenantId;

public final class AuditRootChainValidator {

    private final EpochRootDerivation rootDerivation;

    public AuditRootChainValidator(EpochRootDerivation rootDerivation) {
        this.rootDerivation = Objects.requireNonNull(rootDerivation, "rootDerivation");
    }

    public void validate(
            TenantId tenantId, List<SignedEpochSeal> seals, AuditRootHead currentRootHead) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(seals, "seals");
        Objects.requireNonNull(currentRootHead, "currentRootHead");
        List<SignedEpochSeal> ordered =
                seals.stream()
                        .sorted(
                                Comparator.comparingLong(
                                        seal -> seal.evidence().material().rootSequence()))
                        .toList();
        Set<Long> sequences = new HashSet<>();
        Set<EpochIdentity> epochs = new HashSet<>();
        AuditHash predecessor = zeroHash(currentRootHead.hash().hashAlgorithmVersion());
        long expectedSequence = 1;
        for (SignedEpochSeal seal : ordered) {
            EpochSealMaterial material = seal.evidence().material();
            if (!material.tenantId().equals(tenantId)) {
                throw new AuditVerificationMismatch("root chain contains another tenant");
            }
            if (!sequences.add(material.rootSequence()) || !epochs.add(material.epoch())) {
                throw new AuditVerificationMismatch("root chain contains duplicate evidence");
            }
            if (material.rootSequence() != expectedSequence) {
                throw new AuditVerificationMismatch("root sequence is not dense from one");
            }
            if (!material.previousRootHash().equals(predecessor)) {
                throw new AuditVerificationMismatch("root predecessor does not match");
            }
            DerivedEpochRoot reproduced = rootDerivation.derive(material);
            if (!reproduced.equals(seal.evidence().derivedRoot())) {
                throw new AuditVerificationMismatch("epoch root does not reproduce");
            }
            predecessor = reproduced.epochRoot();
            expectedSequence++;
        }
        if (currentRootHead.sequence() != ordered.size()
                || !currentRootHead.hash().equals(predecessor)) {
            throw new AuditVerificationMismatch("current root head does not match the final seal");
        }
    }

    private static AuditHash zeroHash(short version) {
        return new AuditHash(version, new byte[AuditHash.LENGTH]);
    }
}
