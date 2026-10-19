package com.yourcompany.payment.shared.repo;

import com.yourcompany.payment.shared.entity.OnlineUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OnlineUserRepository extends JpaRepository<OnlineUser, String> {

    /**
     * Deliberately NOT findByUsernameAndActive. The active check happens in the service
     * so an inactive user and an unknown user take the same code path and the same time,
     * and so the audit row can say which it was.
     */
    Optional<OnlineUser> findByUsername(String username);
}
