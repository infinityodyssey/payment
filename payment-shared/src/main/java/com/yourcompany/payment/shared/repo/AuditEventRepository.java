package com.yourcompany.payment.shared.repo;

import com.yourcompany.payment.shared.entity.AuditEvent;
import org.springframework.data.repository.Repository;

/**
 * Extends the bare Repository marker rather than JpaRepository on purpose: the ledger
 * exposes no delete or update operation to application code.
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {
    AuditEvent save(AuditEvent event);
}
