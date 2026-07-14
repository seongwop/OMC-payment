# Common Module

## 개요
모든 마이크로서비스에서 공유하는 공통 컴포넌트를 제공하는 라이브러리 모듈입니다.
bootJar 없이 일반 jar로 빌드되어 다른 서비스 모듈의 의존성으로 사용됩니다.

## 패키지 구조

```
com.omc.common
├── dto/        # 공통 DTO 클래스
├── event/      # Kafka 이벤트 클래스
├── exception/  # 공통 예외 클래스 및 ErrorCode
└── response/   # 공통 API 응답 래퍼
```

## 주요 클래스

| 클래스 | 설명 |
|--------|------|
| `ApiResponse<T>` | 표준 API 응답 래퍼 |
| `ErrorCode` | 전역 에러 코드 Enum |
| `BusinessException` | 비즈니스 로직 예외 베이스 클래스 |
| `KafkaEvent` | Kafka 이벤트 베이스 클래스 |
