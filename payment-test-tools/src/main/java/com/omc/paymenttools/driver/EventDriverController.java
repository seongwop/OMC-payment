package com.omc.paymenttools.driver;

import com.omc.paymenttools.driver.dto.OrderCreatedRequest;
import com.omc.paymenttools.driver.dto.PublishedEventResponse;
import com.omc.paymenttools.driver.dto.RefundRequestedRequest;
import com.omc.paymenttools.driver.dto.StockFailedRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/test/events")
@RequiredArgsConstructor
public class EventDriverController {

    private final EventDriverService eventDriverService;

    // 주문 생성 이벤트 주입
    @PostMapping("/order-created")
    public ResponseEntity<PublishedEventResponse> publishOrderCreated(
            @Valid @RequestBody OrderCreatedRequest request
    ) {
        return ResponseEntity.accepted().body(eventDriverService.publishOrderCreated(request));
    }

    // 환불 요청 이벤트 주입
    @PostMapping("/refund-requested")
    public ResponseEntity<PublishedEventResponse> publishRefundRequested(
            @Valid @RequestBody RefundRequestedRequest request
    ) {
        return ResponseEntity.accepted().body(eventDriverService.publishRefundRequested(request));
    }

    // 재고 차감 실패 이벤트 주입
    @PostMapping("/stock-failed")
    public ResponseEntity<PublishedEventResponse> publishStockFailed(
            @Valid @RequestBody StockFailedRequest request
    ) {
        return ResponseEntity.accepted().body(eventDriverService.publishStockFailed(request));
    }
}
