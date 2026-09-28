import org.meldtech.platform.shared.kernel.identity.CandidateId;
import org.meldtech.platform.shared.kernel.identity.TenantId;

final class CandidateAsTenant {

    void requireTenant(TenantId tenantId) {}

    void violate(CandidateId candidateId) {
        requireTenant(candidateId);
    }
}
