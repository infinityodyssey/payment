package com.yourcompany.payment.shared.repo;

import com.yourcompany.payment.shared.entity.PaymentVendor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentVendorRepository extends JpaRepository<PaymentVendor, String> {

    /**
     * Not findByVendorCodeAndActive: the service needs to tell "no such vendor" from
     * "vendor is switched off", because the second deserves a message the borrower can
     * act on and the first is a client bug.
     */
    Optional<PaymentVendor> findByVendorCode(String vendorCode);

    List<PaymentVendor> findByActiveOrderByPriorityAsc(String active);
}
