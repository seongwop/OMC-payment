# Common Module

팀 프로젝트 기준 코드에서 Payment Service가 의존하던 공통 계약을 함께 분리한 라이브러리 모듈입니다. 실행 애플리케이션이 아니므로 `bootJar`를 만들지 않으며, 결제 서비스에서 실제 사용하는 API 응답·예외·보안 헤더·이벤트 기반 타입을 제공합니다.

| 패키지 | 역할 |
| --- | --- |
| `config`, `security` | Gateway header 인증과 tracing 자동 설정 |
| `entity` | 감사 필드를 포함한 JPA 기반 엔티티 |
| `event` | Kafka 이벤트 공통 계약 |
| `exception`, `handler` | 비즈니스 예외와 API 오류 응답 변환 |
| `response` | 공통 API·페이지 응답 |
| `util` | UUID v7과 페이지 요청 유틸리티 |

결제 서비스가 사용하는 공통 타입만 포함하고, 서비스별 비즈니스 로직은 두지 않습니다.
