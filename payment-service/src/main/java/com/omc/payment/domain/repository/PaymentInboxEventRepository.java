package com.omc.payment.domain.repository;

import com.omc.payment.domain.entity.PaymentInboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentInboxEventRepository extends JpaRepository<PaymentInboxEvent, String> {
}
