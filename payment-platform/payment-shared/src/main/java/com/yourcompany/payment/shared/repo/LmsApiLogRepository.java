package com.yourcompany.payment.shared.repo;

import com.yourcompany.payment.shared.entity.LmsApiLog;
import org.springframework.data.repository.Repository;

/** Insert only. Purging is a privileged job, not something a service can do. */
public interface LmsApiLogRepository extends Repository<LmsApiLog, Long> {
    LmsApiLog save(LmsApiLog log);
}
