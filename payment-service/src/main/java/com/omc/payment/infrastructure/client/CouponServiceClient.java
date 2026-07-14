package com.omc.payment.infrastructure.client;

import com.omc.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "coupon-service")
public interface CouponServiceClient {

    // 결제 직전에 쿠폰을 선점하고 검증
    @PostMapping("/internal/v1/coupons/reserve")
    ApiResponse<UserCouponResponse> reserveCoupon(@RequestBody CouponReserveRequest request);
}
