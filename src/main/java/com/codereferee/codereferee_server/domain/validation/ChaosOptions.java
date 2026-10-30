package com.codereferee.codereferee_server.domain.validation;

/**
 * 어떤 카오스 실험으로 검증할지에 대한 요청자의 선택.
 *
 * BE는 값의 의미를 모른다. 어떤 실험이 존재하는지는 샌드박스가 알고, 유효한 조합인지는
 * AI가 판정한다. 확정 아키텍처의 "BE는 K8s를 모른다" 원칙에 따른 것이며,
 * BE는 형식만 확인하고 그대로 전달·보존한다.
 *
 * 두 값을 묶어 두면 나중에 chaosTarget 같은 항목이 늘어도 TaskStatus의
 * 생성자 인자가 계속 길어지지 않는다.
 */
public record ChaosOptions(String mode, String deploymentProfile, Integer targetUsers) {

    public static final ChaosOptions NONE = new ChaosOptions(null, null, null);

    public static ChaosOptions of(String mode, String deploymentProfile) {
        return of(mode, deploymentProfile, null);
    }

    /**
     * targetUsers: 요청자가 예상하는 동시 사용자 수. 있으면 AI가 그 인원에서의 실패를 Fail 근거로 쓴다.
     * 근거 없는 임계값으로 Fail을 내지 않는다는 원칙 때문에, 기준은 요청자만 정할 수 있다.
     */
    public static ChaosOptions of(String mode, String deploymentProfile, Integer targetUsers) {
        return mode == null && deploymentProfile == null && targetUsers == null
                ? NONE : new ChaosOptions(mode, deploymentProfile, targetUsers);
    }

    public boolean isEmpty() {
        return mode == null && deploymentProfile == null && targetUsers == null;
    }
}
