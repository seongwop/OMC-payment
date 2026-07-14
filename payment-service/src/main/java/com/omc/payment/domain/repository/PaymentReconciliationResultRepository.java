package com.omc.payment.domain.repository;

import com.omc.payment.domain.entity.PaymentReconciliationResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentReconciliationResultRepository extends JpaRepository<PaymentReconciliationResult, UUID> {
}
