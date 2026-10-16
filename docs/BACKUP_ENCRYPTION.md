# 암호화 백업 형식

`.dsbackup`은 기존 ZIP 복구 자료를 감싼 버전 1 형식이다. 문서 열기에서 헤더를 판별해 암호를 요청한다. 암호 없이 일반 ZIP으로 취급하지 않는다. 암호가 맞으면 태그를 검증한 후 기존 ZIP의 경로·파트 수·압축 해제 용량·문서 형식 검사를 그대로 적용한다. 다른 이름의 새 복구 문서로 가져오며, 원본 파일을 변경하지 않는다.

| 바이트 위치 | 내용 |
|---|---|
| 0–7 | ASCII DSBKP001 |
| 8–11 | big-endian PBKDF2 반복 수 600000 |
| 12–27 | SecureRandom salt 16바이트 |
| 28–39 | SecureRandom GCM nonce 12바이트 |
| 40 이후 | AES-256-GCM 암호문과 마지막 16바이트 인증 태그 |

헤더 40바이트 전체를 GCM AAD로 인증한다. PBKDF2WithHmacSHA256은 암호에서 256비트 키를 만들며 별도 JCA provider를 지정하지 않는다. 파일마다 새 salt/nonce를 만든다. 현재 버전은 다른 반복 수를 거부해 악성 헤더의 과도한 KDF 작업을 막는다. 인증이 끝날 때까지 출력은 앱 전용 임시 경로에 있으며, 실패하면 삭제하고 기존 대상은 유지한다. 암호화 파일 전체는 최대 32 MiB이다. 암호화는 문서·편집·필기·정밀 편집 복구 자료를 함께 보호한다.

암호는 8~256자이며 복구에는 같은 암호가 필요하다. 암호는 SharedPreferences/작업 복구 JSON/로그에 기록하지 않는다. 파일 선택기 동안 ViewModel 메모리에만 보관하고, 취소·완료·ViewModel 정리 때 사용한 CharArray를 지운다. Android 텍스트 입력의 String 복사까지 메모리 삭제를 보장하지는 않는다. 프로세스가 종료되면 암호를 다시 입력해야 한다. 키 복구/암호 재설정 서비스는 없다.

설계 기준: [Android 암호화 지침](https://developer.android.com/privacy-and-security/cryptography), [OWASP PBKDF2 작업량 지침](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html). 플랫폼 JCA를 사용하며 자체 암호 알고리즘을 구현하지 않는다. 검증용 `backup-golden.b64`의 암호/내용은 공개 테스트 자료이며 실제 사용자 비밀이 아니다.
