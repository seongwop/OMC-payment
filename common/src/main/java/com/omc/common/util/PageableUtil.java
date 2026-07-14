package com.omc.common.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Set;

public class PageableUtil {

    private static final Set<Integer> ALLOWED_SIZES = Set.of(10, 30, 50);
    private static final int DEFAULT_SIZE = 10;

    private PageableUtil() {
    }

    public static Pageable validatePageSize(Pageable pageable) {
        if (ALLOWED_SIZES.contains(pageable.getPageSize())) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), DEFAULT_SIZE, pageable.getSort());
    }
}
