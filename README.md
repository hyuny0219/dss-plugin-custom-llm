# Custom LLM - Dataiku Plugin

이 플러그인은 Dataiku에서 외부 LLM API(예: Custom LLM)를 쉽게 연결하여 사용할 수 있도록 지원합니다.

## 주요 기능
- Dataiku LLM Mesh 및 Prompt Studio에서 Custom LLM API 연동
- Chat Completion, Embedding 등 다양한 LLM 엔드포인트 지원
- 최대 4개의 커스텀 HTTP 헤더(키/값 쌍) 입력 가능
- Access Token(Authorization) 직접 입력 방식 지원

## 지원 환경 및 제한 사항
- Dataiku DSS 13.x 이상 필요
- 외부 LLM API(예: Custom LLM) 사용 권한 및 토큰 필요
- API 엔드포인트, 모델명 등은 직접 입력해야 함

## 설치 및 설정 방법
1. 플러그인 설치: [Dataiku 플러그인 설치 가이드](https://doc.dataiku.com/dss/latest/plugins/installing.html) 참고
2. Dataiku에서 플러그인 설정:
    - **Access Token**: API 인증용 토큰을 직접 입력 (Bearer ... 형태)
    - **Endpoint URL**: LLM API의 엔드포인트 URL 입력 (예: https://api.example.com/v1/chat/completions)
    - **Model Key**: 사용할 모델명 입력 (예: gpt-3.5, solar-pro 등)
    - **Input Type**: (임베딩 모델용) query 또는 passage
    - **Custom Header 1~4 Key/Value**: 필요 시 추가 HTTP 헤더를 키/값 쌍으로 입력 (예: X-Org-Id, X-User-Id 등)
    - 기타 네트워크/재시도 옵션 등

### 파라미터 예시
| 파라미터명         | 설명                                 | 예시값                        |
|-------------------|--------------------------------------|-------------------------------|
| access_token      | API 인증 토큰 (Bearer 포함/미포함)    | Bearer sk-xxxx...             |
| endpoint_url      | LLM API 엔드포인트                   | https://api.example.com/v1/chat/completions |
| model             | 모델명                               | gpt-3.5, solar-pro            |
| inputType         | (임베딩용) query/passage             | query                         |
| header1_key       | 커스텀 헤더1 이름                    | X-Org-Id                      |
| header1_value     | 커스텀 헤더1 값                      | my-org                        |
| header2_key       | 커스텀 헤더2 이름                    | X-User-Id                     |
| header2_value     | 커스텀 헤더2 값                      | user-123                      |

## 보안 및 주의사항
- Access Token 등 민감 정보는 Dataiku 내에서 안전하게 관리하세요.
- 커스텀 헤더 입력 시 개인정보/보안정보 노출에 유의하세요.
- 플러그인 소스코드 및 설정은 git 등 버전관리 시스템에 안전하게 관리하세요.

## 예시 스크린샷
(아래 이미지는 실제 환경에 맞게 교체/추가하세요)

![api key preset screenshot](assets/api-key-preset-screenshot.png)
![credentials screenshot](assets/credentials-screenshot.png)
![new custom connection screenshot](assets/new-custom-connection-screenshot.png)
![custom custom connection screenshot](assets/custom-custom-connection.png)

## 변경 이력
- Upstage 기반 → Custom LLM 일반화
- Access Token 직접 입력 방식으로 변경
- Preset/키 관리 방식 제거
- 커스텀 헤더(키/값 쌍) 최대 4개 지원
- 문서 및 파라미터 설명 최신화 